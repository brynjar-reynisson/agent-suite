import fs from 'node:fs'
import path from 'node:path'
import morgan from 'morgan'
import type { Plugin, ResolvedConfig } from 'vite'

export interface MorganPluginOptions {
  devLogFile: string
  previewLogFile: string
  format?: string
}

export function morganPlugin(options: MorganPluginOptions): Plugin {
  const format = options.format ?? 'combined'
  let root = process.cwd()

  return {
    name: 'morgan-access-log',

    configResolved(config: ResolvedConfig) {
      root = config.root
    },

    configureServer(server) {
      if (!server.httpServer) return
      const logPath = path.resolve(root, options.devLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.httpServer.on('close', () => stream.end())
      server.middlewares.use(morgan(format, { stream }))
    },

    configurePreviewServer(server) {
      if (!server.httpServer) return
      const logPath = path.resolve(root, options.previewLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.httpServer.on('close', () => stream.end())
      server.middlewares.use(morgan(format, { stream }))
    },
  }
}
