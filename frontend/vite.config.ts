import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(() => {
  return {
    plugins: [react()],
    server: {
      port: 5177,
      host: '0.0.0.0',
      allowedHosts: ['dev.agent.breynisson.org'],
      proxy: {
        '/ai': {
          target: 'http://localhost:8090',
          changeOrigin: true,
        },
        '/audio': {
          target: 'http://localhost:8090',
          changeOrigin: true,
        },
      },
    },
    preview: {
      port: 5176,
      host: '0.0.0.0',
      allowedHosts: ['agent.breynisson.org'],
      proxy: {
        '/ai': {
          target: 'http://localhost:8091',
          changeOrigin: true,
        },
        '/audio': {
          target: 'http://localhost:8091',
          changeOrigin: true,
        },
      },
    },
  }
})
