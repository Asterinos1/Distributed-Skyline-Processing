/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        dark: {
          bg: "#0B0F19",
          card: "#161D30",
          border: "#24324F",
          text: "#F3F4F6",
        }
      }
    },
  },
  plugins: [],
}
