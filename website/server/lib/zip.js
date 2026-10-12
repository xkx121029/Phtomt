import zlib from 'node:zlib'

/**
 * 极简单文件 ZIP 读写。
 *
 * 为什么自己写、不引 archiver / adm-zip：本仓库只在「把 APK 打包成 zip 传到蓝奏云」
 * 与「从蓝奏云取回再还原成 APK」两处用到，完全不需要完整的 zip 库；而 Dockerfile 里
 * `npm ci --omit=dev` 依赖 package-lock.json，多一个依赖就多一次 lock 同步与镜像体积。
 * 单文件 zip 的格式是稳定的公开规范，这里按规范手写，行为完全可控。
 *
 * 只覆盖两个必需动作：
 *  - [zipSingleFile]：把一个文件塞进 zip（默认 store 不压缩——APK 本身已是压缩包，
 *    再 deflate 一遍几乎不省体积，还白白吃 CPU）；调用方可要求 deflate。
 *  - [readFirstFile]：从 zip 里取出第一个文件（蓝奏云回来的包就是我们自己打的，含单个 APK）。
 */

/** CRC-32（ZIP 规范附录里的标准查表法） */
const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n += 1) {
    let c = n
    for (let k = 0; k < 8; k += 1) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    }
    table[n] = c
  }
  return table
})()

function crc32(buf) {
  let c = -1
  for (let i = 0; i < buf.length; i += 1) {
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  }
  return (c ^ -1) >>> 0
}

/** 把当前时间转成 DOS 时间戳（zip 头用的 2 字节 time + 2 字节 date） */
function dosDateTime(date = new Date()) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1)
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()
  return { time, day }
}

/**
 * 打包单个文件。
 * @param {string} name zip 内的文件名（仅取 basename，避免路径穿越/多余目录）
 * @param {Buffer} content 文件内容
 * @param {{deflate?: boolean, date?: Date}} [options]
 * @returns {Buffer}
 */
export function zipSingleFile(name, content, options = {}) {
  const safeName = String(name).split(/[\\/]/).pop() || 'file'
  const nameBuf = Buffer.from(safeName, 'utf8')
  const deflate = options.deflate === true
  const raw = Buffer.isBuffer(content) ? content : Buffer.from(content)
  const body = deflate ? zlib.deflateRawSync(raw) : raw
  const method = deflate ? 8 : 0
  const { time, day } = dosDateTime(options.date)
  const crc = crc32(raw)
  // 0x0800 = 文件名按 UTF-8 编码（中文名必需，否则解压端乱码）
  const flags = 0x0800

  const local = Buffer.alloc(30)
  local.writeUInt32LE(0x04034b50, 0)
  local.writeUInt16LE(20, 4) // 需要的版本
  local.writeUInt16LE(flags, 6)
  local.writeUInt16LE(method, 8)
  local.writeUInt16LE(time, 10)
  local.writeUInt16LE(day, 12)
  local.writeUInt32LE(crc, 14)
  local.writeUInt32LE(body.length, 18)
  local.writeUInt32LE(raw.length, 22)
  local.writeUInt16LE(nameBuf.length, 26)
  local.writeUInt16LE(0, 28) // extra 长度

  const central = Buffer.alloc(46)
  central.writeUInt32LE(0x02014b50, 0)
  central.writeUInt16LE(20, 4) // version made by
  central.writeUInt16LE(20, 6) // version needed
  central.writeUInt16LE(flags, 8)
  central.writeUInt16LE(method, 10)
  central.writeUInt16LE(time, 12)
  central.writeUInt16LE(day, 14)
  central.writeUInt32LE(crc, 16)
  central.writeUInt32LE(body.length, 20)
  central.writeUInt32LE(raw.length, 24)
  central.writeUInt16LE(nameBuf.length, 28)
  central.writeUInt16LE(0, 30) // extra
  central.writeUInt16LE(0, 32) // comment
  central.writeUInt16LE(0, 34) // 起始磁盘号
  central.writeUInt16LE(0, 36) // 内部属性
  central.writeUInt32LE(0, 38) // 外部属性
  central.writeUInt32LE(0, 42) // 本地头偏移（单文件恒为 0）

  const centralSize = central.length + nameBuf.length
  const centralOffset = local.length + nameBuf.length + body.length

  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(0, 4) // 当前磁盘
  end.writeUInt16LE(0, 6) // 中央目录起始磁盘
  end.writeUInt16LE(1, 8) // 本盘条目数
  end.writeUInt16LE(1, 10) // 总条目数
  end.writeUInt32LE(centralSize, 12)
  end.writeUInt32LE(centralOffset, 16)
  end.writeUInt16LE(0, 20) // 注释长度

  return Buffer.concat([local, nameBuf, body, central, nameBuf, end])
}

/**
 * 读出 zip 里第一个文件的原始内容。
 *
 * 走中央目录找条目（不解压就不知道名字，但这里只需要「唯一的那个文件」），
 * 按 method 决定是否 inflateRaw。解析失败返回 null，交由调用方回退。
 */
export function readFirstFile(buffer) {
  try {
    const buf = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer)
    // 从尾部扫 End of Central Directory：注释最长 65535，留出余量
    let eocd = -1
    for (let i = buf.length - 22; i >= 0; i -= 1) {
      if (buf.readUInt32LE(i) === 0x06054b50) {
        eocd = i
        break
      }
    }
    if (eocd < 0) return null
    const total = buf.readUInt16LE(eocd + 10)
    const centralOffset = buf.readUInt32LE(eocd + 16)
    if (!total) return null

    let p = centralOffset
    if (buf.readUInt32LE(p) !== 0x02014b50) return null
    const method = buf.readUInt16LE(p + 10)
    const compSize = buf.readUInt32LE(p + 20)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const commentLen = buf.readUInt16LE(p + 32)
    const localOffset = buf.readUInt32LE(p + 42)
    const name = buf.subarray(p + 46, p + 46 + nameLen).toString('utf8')
    void extraLen
    void commentLen

    if (buf.readUInt32LE(localOffset) !== 0x04034b50) return null
    const localNameLen = buf.readUInt16LE(localOffset + 26)
    const localExtraLen = buf.readUInt16LE(localOffset + 28)
    const dataStart = localOffset + 30 + localNameLen + localExtraLen
    const data = buf.subarray(dataStart, dataStart + compSize)
    const content = method === 8 ? zlib.inflateRawSync(data) : Buffer.from(data)
    return { name, content }
  } catch {
    return null
  }
}
