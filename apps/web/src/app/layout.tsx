import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";

import { Providers } from "@/app/providers";

import "./globals.css";

export const metadata: Metadata = {
  title: "AI 量化研究助手",
  description: "以 Evidence 为核心的美股研究工作台。",
};

export const viewport: Viewport = {
  colorScheme: "light",
  themeColor: "#ffffff",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html data-scroll-behavior="smooth" lang="zh-CN">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
