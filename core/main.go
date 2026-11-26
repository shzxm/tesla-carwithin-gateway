package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

type Config struct {
	Domain             string `json:"domain"`
	DDNSIP             string `json:"ddns_ip"`
	AutoDDNS           bool   `json:"auto_ddns"`
	ListenPort         int    `json:"listen_port"`
	ControlPort        int    `json:"control_port"`
	InitialUpstream    string `json:"initial_upstream"`
	TLSMode            string `json:"tls_mode"` // "cloudflare" or "manual"
	CloudflareAPIToken string `json:"cloudflare_api_token"`
	ACMEEmail          string `json:"acme_email"`
	CertFile           string `json:"cert_file"`
	KeyFile            string `json:"key_file"`
	DataDir            string `json:"data_dir"`
}

func loadConfig(path string) (Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	cfg := Config{
		DDNSIP:      "100.64.0.1",
		AutoDDNS:    true,
		ListenPort:  8443,
		ControlPort: 18443,
		TLSMode:     "cloudflare",
		DataDir:     ".",
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return Config{}, err
	}
	if strings.TrimSpace(cfg.DDNSIP) == "" {
		cfg.DDNSIP = "100.64.0.1"
	}
	if cfg.ListenPort <= 0 {
		cfg.ListenPort = 8443
	}
	if cfg.ControlPort <= 0 {
		cfg.ControlPort = 18443
	}
	return cfg, nil
}

func main() {
	configPath := flag.String("config", "config.json", "Path to gateway JSON config")
	action := flag.String("action", "serve", "Action: serve | list-zones | ddns | cert")
	flag.Parse()

	log.SetFlags(log.Ltime | log.Lmicroseconds)
	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("[core] failed to load config: %v", err)
	}

	switch strings.ToLower(strings.TrimSpace(*action)) {
	case "list-zones":
		runListZones(cfg)
		return
	case "ddns":
		runDDNS(cfg)
		return
	case "cert":
		runCertOnly(cfg)
		return
	case "serve", "":
		runServe(cfg)
		return
	default:
		log.Fatalf("[core] unknown action: %s", *action)
	}
}

func runListZones(cfg Config) {
	if strings.TrimSpace(cfg.CloudflareAPIToken) == "" {
		log.Fatalf("[cf] cloudflare_api_token is empty")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	zones, err := newCFClient(cfg.CloudflareAPIToken).listZones(ctx)
	if err != nil {
		log.Fatalf("[cf] list zones failed: %v", err)
	}
	out, _ := json.Marshal(zones)
	fmt.Println(string(out))
}

func runDDNS(cfg Config) {
	if strings.TrimSpace(cfg.CloudflareAPIToken) == "" {
		log.Fatalf("[ddns] cloudflare_api_token is empty")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	status, err := newCFClient(cfg.CloudflareAPIToken).upsertARecord(ctx, cfg.Domain, cfg.DDNSIP)
	if err != nil {
		log.Fatalf("[ddns] sync failed: %v", err)
	}
	log.Printf("[ddns] %s -> %s (%s, proxied=false)", cfg.Domain, cfg.DDNSIP, status)
}

func runCertOnly(cfg Config) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	certMgr := NewCertManager(cfg)
	if err := certMgr.EnsureLoaded(ctx); err != nil {
		log.Fatalf("[tls] certificate check/issue failed: %v", err)
	}
	if cur := certMgr.GetCurrent(); cur != nil && cur.Leaf != nil {
		log.Printf("[tls] certificate ready: CN=%s, NotAfter=%s", cur.Leaf.Subject.CommonName, cur.Leaf.NotAfter.Format("2006-01-02"))
	} else {
		log.Printf("[tls] certificate ready")
	}
}

func runServe(cfg Config) {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if cfg.AutoDDNS && strings.TrimSpace(cfg.CloudflareAPIToken) != "" && strings.TrimSpace(cfg.Domain) != "" {
		ddnsCtx, dCancel := context.WithTimeout(ctx, 20*time.Second)
		status, err := newCFClient(cfg.CloudflareAPIToken).upsertARecord(ddnsCtx, cfg.Domain, cfg.DDNSIP)
		dCancel()
		if err != nil {
			log.Printf("[ddns] auto-sync warning (continuing): %v", err)
		} else {
			log.Printf("[ddns] auto-sync ok: %s -> %s (%s)", cfg.Domain, cfg.DDNSIP, status)
		}
	}

	certMgr := NewCertManager(cfg)
	initCtx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	if err := certMgr.EnsureLoaded(initCtx); err != nil {
		cancel()
		log.Fatalf("[core] TLS initialization failed: %v", err)
	}
	cancel()
	certMgr.StartAutoRenewLoop(ctx)

	engine := NewProxyEngine(cfg.InitialUpstream)

	ctrlSrv := &http.Server{
		Addr:    fmt.Sprintf("127.0.0.1:%d", cfg.ControlPort),
		Handler: engine.ControlHandler(cfg.Domain, cfg.ListenPort),
	}
	go func() {
		log.Printf("[core] control API listening on %s", ctrlSrv.Addr)
		if err := ctrlSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("[core] control API stopped: %v", err)
		}
	}()

	httpsSrv := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.ListenPort),
		Handler: engine,
		TLSConfig: &tls.Config{
			MinVersion:     tls.VersionTLS12,
			GetCertificate: certMgr.GetCertificate,
		},
	}

	go func() {
		log.Printf("[core] HTTPS/WebSocket proxy listening on :%d for https://%s:%d", cfg.ListenPort, cfg.Domain, cfg.ListenPort)
		if err := httpsSrv.ListenAndServeTLS("", ""); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[core] HTTPS server error: %v", err)
		}
	}()

	<-ctx.Done()
	log.Printf("[core] shutting down...")
	shutdownCtx, sCancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer sCancel()
	_ = httpsSrv.Shutdown(shutdownCtx)
	_ = ctrlSrv.Shutdown(shutdownCtx)
}
