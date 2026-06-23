import fs from 'node:fs'
import path from 'node:path'
import morgan from 'morgan'
import type { Plugin, ResolvedConfig, Connect } from 'vite'

export interface MorganPluginOptions {
  devLogFile: string
  previewLogFile: string
  format?: string
}

function attachMorgan(
  middlewares: Connect.Server,
  stream: fs.WriteStream,
  format: string,
) {
  // Log immediately on request arrival so SSE streams that never complete still appear.
  middlewares.use(morgan(':date[iso] --> :method :url', { stream, immediate: true }))
  // Log again on completion with status code, response time, and bytes.
  middlewares.use(morgan(format, { stream }))
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
      attachMorgan(server.middlewares, stream, format)
    },

    configurePreviewServer(server) {
      if (!server.httpServer) return
      const logPath = path.resolve(root, options.previewLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.httpServer.on('close', () => stream.end())
      attachMorgan(server.middlewares, stream, format)
    },
  }
}
