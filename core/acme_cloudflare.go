package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/crypto/acme"
)

const letsEncryptProdURL = "https://acme-v02.api.letsencrypt.org/directory"

type cfClient struct {
	token string
	http  *http.Client
}

func newCFClient(token string) *cfClient {
	return &cfClient{
		token: strings.TrimSpace(token),
		http:  &http.Client{Timeout: 20 * time.Second},
	}
}

func (c *cfClient) doJSON(ctx context.Context, method, endpoint string, body any, out any) error {
	var r io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		r = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, "https://api.cloudflare.com/client/v4"+endpoint, r)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("cloudflare api %s %s failed (%d): %s", method, endpoint, resp.StatusCode, string(raw))
	}
	if out != nil {
		if err := json.Unmarshal(raw, out); err != nil {
			return err
		}
	}
	return nil
}

// findZoneID walks up domain labels to locate the matching Cloudflare zone.
func (c *cfClient) findZoneID(ctx context.Context, domain string) (string, error) {
	parts := strings.Split(strings.TrimSuffix(domain, "."), ".")
	for i := 0; i < len(parts)-1; i++ {
		candidate := strings.Join(parts[i:], ".")
		var res struct {
			Success bool `json:"success"`
			Result  []struct {
				ID   string `json:"id"`
				Name string `json:"name"`
			} `json:"result"`
		}
		if err := c.doJSON(ctx, http.MethodGet, "/zones?name="+candidate, nil, &res); err != nil {
			return "", err
		}
		if res.Success && len(res.Result) > 0 {
			return res.Result[0].ID, nil
		}
	}
	return "", fmt.Errorf("cloudflare zone not found for domain %s", domain)
}

func (c *cfClient) createTXTRecord(ctx context.Context, zoneID, name, content string) (string, error) {
	var res struct {
		Success bool `json:"success"`
		Result  struct {
			ID string `json:"id"`
		} `json:"result"`
	}
	payload := map[string]any{
		"type":    "TXT",
		"name":    name,
		"content": content,
		"ttl":     60,
	}
	if err := c.doJSON(ctx, http.MethodPost, "/zones/"+zoneID+"/dns_records", payload, &res); err != nil {
		return "", err
	}
	if !res.Success || res.Result.ID == "" {
		return "", errors.New("cloudflare failed to create TXT record")
	}
	return res.Result.ID, nil
}

func (c *cfClient) deleteTXTRecord(ctx context.Context, zoneID, recordID string) {
	if zoneID == "" || recordID == "" {
		return
	}
	_ = c.doJSON(ctx, http.MethodDelete, "/zones/"+zoneID+"/dns_records/"+recordID, nil, nil)
}

func loadOrCreateAccountKey(path string) (*ecdsa.PrivateKey, error) {
	if raw, err := os.ReadFile(path); err == nil {
		block, _ := pem.Decode(raw)
		if block != nil {
			if key, err := x509.ParseECPrivateKey(block.Bytes); err == nil {
				return key, nil
			}
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der})
	if err := os.WriteFile(path, pemBytes, 0600); err != nil {
		return nil, err
	}
	return key, nil
}

// issueCertWithCloudflare obtains a Let's Encrypt certificate via DNS-01 using Cloudflare API.
func issueCertWithCloudflare(ctx context.Context, domain, email, cfToken, dataDir, certPath, keyPath string) error {
	if domain == "" {
		return errors.New("domain is required for ACME DNS-01")
	}
	if cfToken == "" {
		return errors.New("cloudflare api token is required for ACME DNS-01")
	}
	if err := os.MkdirAll(dataDir, 0700); err != nil {
		return err
	}

	acctKey, err := loadOrCreateAccountKey(filepath.Join(dataDir, "acme-account.key"))
	if err != nil {
		return fmt.Errorf("account key: %w", err)
	}

	client := &acme.Client{
		Key:          acctKey,
		DirectoryURL: letsEncryptProdURL,
	}

	acct := &acme.Account{}
	if strings.TrimSpace(email) != "" {
		acct.Contact = []string{"mailto:" + strings.TrimSpace(email)}
	}
	if _, err := client.Register(ctx, acct, acme.AcceptTOS); err != nil && !errors.Is(err, acme.ErrAccountAlreadyExists) {
		return fmt.Errorf("acme register: %w", err)
	}

	log.Printf("[acme] creating DNS-01 order for %s", domain)
	order, err := client.AuthorizeOrder(ctx, acme.DomainIDs(domain))
	if err != nil {
		return fmt.Errorf("authorize order: %w", err)
	}

	cf := newCFClient(cfToken)
	zoneID, err := cf.findZoneID(ctx, domain)
	if err != nil {
		return err
	}

	for _, authzURL := range order.AuthzURLs {
		authz, err := client.GetAuthorization(ctx, authzURL)
		if err != nil {
			return fmt.Errorf("get authz: %w", err)
		}
		if authz.Status == acme.StatusValid {
			continue
		}
		var chal *acme.Challenge
		for _, ch := range authz.Challenges {
			if ch.Type == "dns-01" {
				chal = ch
				break
			}
		}
		if chal == nil {
			return errors.New("no dns-01 challenge offered by Let's Encrypt")
		}

		txtVal, err := client.DNS01ChallengeRecord(chal.Token)
		if err != nil {
			return err
		}
		recordName := "_acme-challenge." + domain
		log.Printf("[acme] creating TXT record %s", recordName)
		recordID, err := cf.createTXTRecord(ctx, zoneID, recordName, txtVal)
		if err != nil {
			return err
		}
		defer cf.deleteTXTRecord(context.Background(), zoneID, recordID)

		log.Printf("[acme] waiting 15s for Cloudflare DNS propagation...")
		select {
		case <-time.After(15 * time.Second):
		case <-ctx.Done():
			return ctx.Err()
		}

		if _, err := client.Accept(ctx, chal); err != nil {
			return fmt.Errorf("accept challenge: %w", err)
		}
		if _, err := client.WaitAuthorization(ctx, authz.URI); err != nil {
			return fmt.Errorf("wait authz: %w", err)
		}
	}

	if _, err := client.WaitOrder(ctx, order.URI); err != nil {
		return fmt.Errorf("wait order: %w", err)
	}

	certKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	csr, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{
		Subject:  pkix.Name{CommonName: domain},
		DNSNames: []string{domain},
	}, certKey)
	if err != nil {
		return err
	}

	derChain, _, err := client.CreateOrderCert(ctx, order.FinalizeURL, csr, true)
	if err != nil {
		return fmt.Errorf("create order cert: %w", err)
	}

	var certPEM bytes.Buffer
	for _, der := range derChain {
		_ = pem.Encode(&certPEM, &pem.Block{Type: "CERTIFICATE", Bytes: der})
	}
	keyDER, err := x509.MarshalECPrivateKey(certKey)
	if err != nil {
		return err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	if err := os.WriteFile(certPath, certPEM.Bytes(), 0644); err != nil {
		return err
	}
	if err := os.WriteFile(keyPath, keyPEM, 0600); err != nil {
		return err
	}
	log.Printf("[acme] certificate issued and saved for %s", domain)
	return nil
}
