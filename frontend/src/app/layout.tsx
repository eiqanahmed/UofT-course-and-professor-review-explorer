import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "UofT Course and Professor Review Explorer",
  description: "Search and compare UofT courses and professors using review evidence.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
