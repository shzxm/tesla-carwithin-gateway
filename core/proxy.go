package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
	"time"
)

// ProxyEngine forwards HTTP and WebSocket traffic to a dynamically updated upstream (iPhone CarWithin).
type ProxyEngine struct {
	mu          sync.RWMutex
	upstreamURL *url.URL
	rawUpstream string
	proxy       *httputil.ReverseProxy
}

func NewProxyEngine(initialUpstream string) *ProxyEngine {
	e := &ProxyEngine{}
	transport := &http.Transport{
		Proxy: nil, // Never use system HTTP proxy for local LAN hotspot traffic
		DialContext: (&net.Dialer{
			Timeout:   5 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     false, // Keep HTTP/1.1 for clean WebSocket upgrades
		MaxIdleConns:          64,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}

	e.proxy = &httputil.ReverseProxy{
		Transport:     transport,
		FlushInterval: -1, // Zero-buffering for low-latency video/WebSocket streams
		Director: func(req *http.Request) {
			target := e.GetUpstream()
			if target == nil {
				return
			}
			req.URL.Scheme = target.Scheme
			req.URL.Host = target.Host
			req.Host = target.Host
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			up := e.GetUpstreamString()
			log.Printf("[proxy] upstream error (%s): %v", up, err)
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusBadGateway)
			_, _ = fmt.Fprintf(w, waitingHTML, up, err.Error())
		},
	}

	if strings.TrimSpace(initialUpstream) != "" {
		_ = e.SetUpstream(initialUpstream)
	}
	return e
}

func (e *ProxyEngine) SetUpstream(raw string) error {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		e.mu.Lock()
		e.upstreamURL = nil
		e.rawUpstream = ""
		e.mu.Unlock()
		return nil
	}
	if !strings.HasPrefix(raw, "http://") && !strings.HasPrefix(raw, "https://") {
		raw = "http://" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return fmt.Errorf("invalid upstream url: %q", raw)
	}
	e.mu.Lock()
	changed := e.rawUpstream != parsed.String()
	e.upstreamURL = parsed
	e.rawUpstream = parsed.String()
	e.mu.Unlock()
	if changed {
		log.Printf("[proxy] upstream updated -> %s", parsed.String())
	}
	return nil
}

func (e *ProxyEngine) GetUpstream() *url.URL {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.upstreamURL
}

func (e *ProxyEngine) GetUpstreamString() string {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.rawUpstream
}

func (e *ProxyEngine) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if e.GetUpstream() == nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = fmt.Fprintf(w, waitingHTML, "未发现主 iPhone (CarWithin)", "请确认主 iPhone 已连接热点并打开 CarWithin（9888 端口）")
		return
	}
	e.proxy.ServeHTTP(w, r)
}

// ControlHandler exposes a localhost-only JSON API for the Android app to hot-swap upstream IP & query health.
func (e *ProxyEngine) ControlHandler(domain string, listenPort int) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status":      "ok",
			"domain":      domain,
			"listen_port": listenPort,
			"upstream":    e.GetUpstreamString(),
		})
	})
	mux.HandleFunc("/upstream", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req struct {
			Upstream string `json:"upstream"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if err := e.SetUpstream(req.Upstream); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok":       true,
			"upstream": e.GetUpstreamString(),
		})
	})
	return mux
}

const waitingHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="3">
<title>CarWithin Gateway - 等待连接</title>
<style>
  body { background:#0b0f19; color:#e5e7eb; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; }
  .card { background:#111827; border:1px solid #1f2937; border-radius:16px; padding:32px; max-width:520px; width:90%%; box-shadow:0 20px 50px rgba(0,0,0,.45); }
  h1 { margin:0 0 12px; font-size:22px; color:#60a5fa; }
  p { margin:8px 0; line-height:1.6; color:#9ca3af; font-size:15px; }
  code { background:#1f2937; color:#f9fafb; padding:2px 8px; border-radius:6px; font-size:14px; }
  .hint { margin-top:16px; padding:12px 14px; background:#0f172a; border-left:3px solid #3b82f6; border-radius:6px; font-size:13px; color:#cbd5e1; }
</style>
</head>
<body>
  <div class="card">
    <h1>CarWithin 本地网关已就绪</h1>
    <p>当前目标上游：<code>%s</code></p>
    <p>状态详情：%s</p>
    <div class="hint">页面每 3 秒自动刷新。请确认主 iPhone 已连接安卓 5 GHz 热点并启动 CarWithin。</div>
  </div>
</body>
</html>`
