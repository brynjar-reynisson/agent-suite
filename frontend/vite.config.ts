import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { morganPlugin } from './plugins/morganPlugin'

export default defineConfig(() => {
  return {
    plugins: [
      react(),
      morganPlugin({
        devLogFile: '../logs/frontend-dev-access.log',
        previewLogFile: '../logs/frontend-prod-access.log',
        format: 'combined',
      }),
    ],
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
