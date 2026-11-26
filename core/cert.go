package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type CertManager struct {
	cfg      Config
	certPath string
	keyPath  string

	mu   sync.RWMutex
	cert *tls.Certificate
}

func NewCertManager(cfg Config) *CertManager {
	certPath := cfg.CertFile
	keyPath := cfg.KeyFile
	if certPath == "" {
		certPath = filepath.Join(cfg.DataDir, "fullchain.pem")
	}
	if keyPath == "" {
		keyPath = filepath.Join(cfg.DataDir, "privkey.pem")
	}
	return &CertManager{
		cfg:      cfg,
		certPath: certPath,
		keyPath:  keyPath,
	}
}

func (m *CertManager) EnsureLoaded(ctx context.Context) error {
	if err := m.tryLoadExisting(); err == nil {
		if m.cfg.TLSMode != "cloudflare" || !m.needsRenewal() {
			log.Printf("[tls] loaded valid certificate from %s", m.certPath)
			return nil
		}
		log.Printf("[tls] existing certificate expiring soon, renewing via Cloudflare DNS-01...")
	} else if m.cfg.TLSMode == "manual" {
		return fmt.Errorf("manual certificate load failed (%s / %s): %w", m.certPath, m.keyPath, err)
	}

	if m.cfg.TLSMode == "cloudflare" {
		if err := issueCertWithCloudflare(ctx, m.cfg.Domain, m.cfg.ACMEEmail, m.cfg.CloudflareAPIToken, m.cfg.DataDir, m.certPath, m.keyPath); err != nil {
			// If we still had a not-yet-expired cert loaded, keep serving it.
			if m.GetCurrent() != nil {
				log.Printf("[tls] renewal failed, keeping existing valid cert: %v", err)
				return nil
			}
			return err
		}
		return m.tryLoadExisting()
	}

	return errors.New("unsupported tls_mode: " + m.cfg.TLSMode)
}

func (m *CertManager) tryLoadExisting() error {
	if _, err := os.Stat(m.certPath); err != nil {
		return err
	}
	if _, err := os.Stat(m.keyPath); err != nil {
		return err
	}
	pair, err := tls.LoadX509KeyPair(m.certPath, m.keyPath)
	if err != nil {
		return err
	}
	if len(pair.Certificate) > 0 {
		leaf, err := x509.ParseCertificate(pair.Certificate[0])
		if err == nil {
			pair.Leaf = leaf
		}
	}
	m.mu.Lock()
	m.cert = &pair
	m.mu.Unlock()
	return nil
}

func (m *CertManager) needsRenewal() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.cert == nil || m.cert.Leaf == nil {
		return true
	}
	return time.Until(m.cert.Leaf.NotAfter) < 30*24*time.Hour
}

func (m *CertManager) GetCurrent() *tls.Certificate {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.cert
}

func (m *CertManager) GetCertificate(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	c := m.GetCurrent()
	if c == nil {
		return nil, errors.New("tls certificate not loaded")
	}
	return c, nil
}

func (m *CertManager) StartAutoRenewLoop(ctx context.Context) {
	if m.cfg.TLSMode != "cloudflare" {
		return
	}
	go func() {
		ticker := time.NewTicker(12 * time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if m.needsRenewal() {
					log.Printf("[tls] background auto-renewal triggered for %s", m.cfg.Domain)
					renewCtx, cancel := context.WithTimeout(ctx, 3*time.Minute)
					if err := m.EnsureLoaded(renewCtx); err != nil {
						log.Printf("[tls] background renewal error: %v", err)
					}
					cancel()
				}
			}
		}
	}()
}
