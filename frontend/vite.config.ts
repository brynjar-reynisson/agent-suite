import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const isProd = mode === 'production'
  return {
    plugins: [react()],
    server: {
      port: isProd ? 5176 : 5177,
      allowedHosts: [isProd ? 'agent.breynisson.org' : 'dev.agent.breynisson.org'],
      proxy: {
        '/ai': {
          target: isProd ? 'http://localhost:8091' : 'http://localhost:8090',
          changeOrigin: true,
        },
      },
    },
  }
})
