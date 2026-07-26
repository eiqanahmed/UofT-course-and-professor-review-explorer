import type { Config } from "tailwindcss";

export default {
  content: ["./src/app/**/*.{ts,tsx}", "./src/components/**/*.{ts,tsx}", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#17202a",
        muted: "#657180",
        line: "#d9e0e8",
        paper: "#f7f9fb",
        uoft: "#1f6fbf",
      },
    },
  },
  plugins: [],
} satisfies Config;
