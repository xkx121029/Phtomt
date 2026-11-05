// 版本号单一真源。
//
// 以前 0.1.0 / 0.4.0 / v0.1 三种口径散在 14 处，读文档的人根本不知道当前是哪一版。
// 现在只有这里 + 各 package.json + android/version.properties 三处需要同步，
// 其余代码（CLI banner、MCP identity）全部从这里取。

export const VERSION = '0.4.0';

/** MCP 协议版本（客户端与服务端必须用同一个常量，别再各自硬编码）。 */
export const MCP_PROTOCOL_VERSION = '2025-03-26';
