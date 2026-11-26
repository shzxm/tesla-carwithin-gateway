package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestProxyHTTPAndHotSwap(t *testing.T) {
	up1 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("carwithin-up1:" + r.URL.Path))
	}))
	defer up1.Close()

	up2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("carwithin-up2:" + r.URL.Path))
	}))
	defer up2.Close()

	engine := NewProxyEngine("")
	proxySrv := httptest.NewServer(engine)
	defer proxySrv.Close()

	// 1. Without upstream, returns 503 waiting page
	resp, err := http.Get(proxySrv.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 when upstream empty, got %d", resp.StatusCode)
	}

	// 2. Hot-swap upstream via Control API
	ctrlSrv := httptest.NewServer(engine.ControlHandler("car.example.com", 8443))
	defer ctrlSrv.Close()

	body, _ := json.Marshal(map[string]string{"upstream": up1.URL})
	cResp, err := http.Post(ctrlSrv.URL+"/upstream", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	cResp.Body.Close()
	if cResp.StatusCode != http.StatusOK {
		t.Fatalf("control /upstream returned %d", cResp.StatusCode)
	}

	resp, err = http.Get(proxySrv.URL + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(got) != "carwithin-up1:/stream" {
		t.Fatalf("unexpected body: %q", string(got))
	}

	// 3. Hot-swap to up2
	body2, _ := json.Marshal(map[string]string{"upstream": up2.URL})
	cResp2, err := http.Post(ctrlSrv.URL+"/upstream", "application/json", bytes.NewReader(body2))
	if err != nil {
		t.Fatal(err)
	}
	cResp2.Body.Close()

	resp, err = http.Get(proxySrv.URL + "/ping")
	if err != nil {
		t.Fatal(err)
	}
	got, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(got) != "carwithin-up2:/ping" {
		t.Fatalf("unexpected body after hot-swap: %q", string(got))
	}
}

func TestProxyWebSocketUpgrade(t *testing.T) {
	// Simulated upstream WebSocket/Upgrade echo server
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			http.Error(w, "upgrade required", http.StatusUpgradeRequired)
			return
		}
		hj, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w, "no hijack", http.StatusInternalServerError)
			return
		}
		conn, rw, err := hj.Hijack()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = rw.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
		_ = rw.Flush()
		line, err := rw.ReadString('\n')
		if err == nil {
			_, _ = rw.WriteString("echo:" + line)
			_ = rw.Flush()
		}
	}))
	defer up.Close()

	engine := NewProxyEngine(up.URL)
	proxySrv := httptest.NewServer(engine)
	defer proxySrv.Close()

	addr := strings.TrimPrefix(proxySrv.URL, "http://")
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))

	_, _ = conn.Write([]byte("GET /ws HTTP/1.1\r\nHost: car.example.com\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"))
	br := bufio.NewReader(conn)
	statusLine, err := br.ReadString('\n')
	if err != nil || !strings.Contains(statusLine, "101") {
		t.Fatalf("expected 101 Switching Protocols, got %q (err=%v)", statusLine, err)
	}
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if line == "\r\n" {
			break
		}
	}
	_, _ = conn.Write([]byte("hello-carplay\n"))
	reply, err := br.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if reply != "echo:hello-carplay\n" {
		t.Fatalf("unexpected ws reply: %q", reply)
	}
}

func TestManualCertManager(t *testing.T) {
	dir := t.TempDir()
	certFile := filepath.Join(dir, "fullchain.pem")
	keyFile := filepath.Join(dir, "privkey.pem")

	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "car.example.com"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(60 * 24 * time.Hour),
		DNSNames:     []string{"car.example.com"},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0644)
	_ = os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0600)

	mgr := NewCertManager(Config{
		Domain:   "car.example.com",
		TLSMode:  "manual",
		CertFile: certFile,
		KeyFile:  keyFile,
		DataDir:  dir,
	})
	if err := mgr.EnsureLoaded(context.Background()); err != nil {
		t.Fatalf("EnsureLoaded failed: %v", err)
	}
	if mgr.needsRenewal() {
		t.Fatalf("60-day cert should not need renewal")
	}
	if _, err := mgr.GetCertificate(nil); err != nil {
		t.Fatalf("GetCertificate failed: %v", err)
	}
}
