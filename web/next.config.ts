import type { NextConfig } from "next";

// Glasses web app (public/g, vanilla JS + KaTeX). Same headers the FastAPI backend set.
const glassesHeaders = [
  {
    key: "Content-Security-Policy",
    value: "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
      "frame-ancestors 'none'; base-uri 'none'",
  },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "no-referrer" }, // the install URL carries a one-time pairing code
  { key: "Cache-Control", value: "no-cache" },
];

const config: NextConfig = {
  async rewrites() {
    return [{ source: "/g", destination: "/g/index.html" }];
  },
  async headers() {
    return [
      { source: "/g", headers: glassesHeaders },
      { source: "/g/:path*", headers: glassesHeaders },
    ];
  },
};

export default config;
