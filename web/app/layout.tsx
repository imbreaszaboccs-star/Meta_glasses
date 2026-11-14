import type { ReactNode } from "react";

export const metadata = { title: "FocusLens" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // ponytail: the Meta display simulator extension injects attributes into <html> before hydration
    <html lang="en" suppressHydrationWarning>
      <body style={{ margin: 0, background: "#000", color: "#fff", fontFamily: "system-ui, sans-serif" }}>
        {children}
      </body>
    </html>
  );
}
