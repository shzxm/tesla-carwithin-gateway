package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCloudflareListZonesAndUpsertDDNS(t *testing.T) {
	var currentIP string
	var currentProxied bool

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-token" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")

		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/zones":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"success": true,
				"result": []map[string]any{
					{"id": "zone-123", "name": "example.com"},
				},
			})
		case r.Method == http.MethodGet && r.URL.Path == "/zones/zone-123/dns_records":
			var records []map[string]any
			if currentIP != "" {
				records = append(records, map[string]any{
					"id":      "rec-1",
					"name":    "car.example.com",
					"content": currentIP,
					"proxied": currentProxied,
				})
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"success": true,
				"result":  records,
			})
		case r.Method == http.MethodPost && r.URL.Path == "/zones/zone-123/dns_records":
			var body struct {
				Content string `json:"content"`
				Proxied bool   `json:"proxied"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			currentIP = body.Content
			currentProxied = body.Proxied
			_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
		case r.Method == http.MethodPut && r.URL.Path == "/zones/zone-123/dns_records/rec-1":
			var body struct {
				Content string `json:"content"`
				Proxied bool   `json:"proxied"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			currentIP = body.Content
			currentProxied = body.Proxied
			_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
		default:
			http.Error(w, "not found: "+r.URL.Path, http.StatusNotFound)
		}
	}))
	defer srv.Close()

	cf := newCFClient("test-token")
	cf.baseURL = srv.URL

	zones, err := cf.listZones(context.Background())
	if err != nil {
		t.Fatalf("listZones failed: %v", err)
	}
	if len(zones) != 1 || zones[0].Name != "example.com" {
		t.Fatalf("unexpected zones: %+v", zones)
	}

	// 1. Create A record
	st, err := cf.upsertARecord(context.Background(), "car.example.com", "100.64.0.1")
	if err != nil || st != "created" {
		t.Fatalf("expected created, got %q (err=%v)", st, err)
	}
	if currentIP != "100.64.0.1" || currentProxied {
		t.Fatalf("unexpected created record: ip=%s proxied=%v", currentIP, currentProxied)
	}

	// 2. Unchanged when same IP & proxied=false
	st, err = cf.upsertARecord(context.Background(), "car.example.com", "100.64.0.1")
	if err != nil || st != "unchanged" {
		t.Fatalf("expected unchanged, got %q (err=%v)", st, err)
	}

	// 3. Updated when IP changes
	st, err = cf.upsertARecord(context.Background(), "car.example.com", "100.64.0.2")
	if err != nil || st != "updated" {
		t.Fatalf("expected updated, got %q (err=%v)", st, err)
	}
	if currentIP != "100.64.0.2" {
		t.Fatalf("expected updated ip 100.64.0.2, got %s", currentIP)
	}
}
