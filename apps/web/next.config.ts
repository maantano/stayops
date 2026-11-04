import type { NextConfig } from "next";

const API_BASE_URL = process.env.NEXT_PUBLIC_STAYOPS_API_BASE_URL ?? "http://localhost:8080";

/**
 * 보안 헤더 (P12 보안 점검). CSP는 production에만 건다 — 개발 서버의 HMR은 eval·websocket이 필요하다.
 * Next의 inline bootstrap script 때문에 script-src에 'unsafe-inline'을 둔다(nonce 방식은 OPEN: 동적 렌더링 필요).
 * API 호출은 NEXT_PUBLIC_STAYOPS_API_BASE_URL(build 시점 값)로만 허용한다.
 */
const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  `connect-src 'self' ${new URL(API_BASE_URL).origin}`,
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "object-src 'none'",
].join("; ");

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  ...(process.env.NODE_ENV === "production" ? [{ key: "Content-Security-Policy", value: contentSecurityPolicy }] : []),
];

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  poweredByHeader: false,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
