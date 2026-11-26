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
	"syscall"
	"time"
)

type Config struct {
	Domain             string `json:"domain"`
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
		ListenPort:  8443,
		ControlPort: 18443,
		TLSMode:     "cloudflare",
		DataDir:     ".",
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return Config{}, err
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
	flag.Parse()

	log.SetFlags(log.Ltime | log.Lmicroseconds)
	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("[core] failed to load config: %v", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

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
