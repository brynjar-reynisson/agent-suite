import fs from 'node:fs'
import path from 'node:path'
import morgan from 'morgan'
import type { Plugin } from 'vite'

export interface MorganPluginOptions {
  devLogFile: string
  previewLogFile: string
  format?: string
}

export function morganPlugin(options: MorganPluginOptions): Plugin {
  const format = options.format ?? 'combined'

  return {
    name: 'morgan-access-log',

    configureServer(server) {
      const logPath = path.resolve(process.cwd(), options.devLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.middlewares.use(morgan(format, { stream }))
    },

    configurePreviewServer(server) {
      const logPath = path.resolve(process.cwd(), options.previewLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.middlewares.use(morgan(format, { stream }))
    },
  }
}
