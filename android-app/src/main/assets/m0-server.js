'use strict';

// SillyTavern Android — M0 真机 PoC 测试服务。
// 目的：验证内置 Node 能否在真机从 nativeLibraryDir 执行，并在本机端口提供 HTTP。
// 仅依赖 Node 核心模块，不需要 node_modules。非完整 SillyTavern。

const http = require('node:http');
const os = require('node:os');

const PORT = Number(process.env.ST_PORT || 8000);
const HOST = '127.0.0.1';

function log(msg) {
    process.stdout.write(`[m0] ${new Date().toISOString()} ${msg}\n`);
}

log(`node ${process.version} ${process.platform}/${process.arch} pid=${process.pid}`);
log(`cwd=${process.cwd()}`);
log(`HOME=${process.env.HOME}`);
log(`TMPDIR=${process.env.TMPDIR}`);
log(`cpus=${os.cpus().length} mem=${Math.round(os.totalmem() / 1048576)}MB`);

const server = http.createServer((req, res) => {
    log(`request ${req.method} ${req.url}`);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(
        '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">' +
        '<meta name="viewport" content="width=device-width, initial-scale=1">' +
        '<title>SillyTavern M0</title></head>' +
        '<body style="font-family:sans-serif;margin:0;padding:24px;line-height:1.7">' +
        '<h1>✅ M0 验证成功</h1>' +
        '<p>内置 Node 已在本机从 nativeLibraryDir 启动，并在本机端口提供 HTTP 服务。</p>' +
        '<ul>' +
        `<li>Node ${process.version}</li>` +
        `<li>平台 ${process.platform}/${process.arch}</li>` +
        `<li>进程号 ${process.pid}</li>` +
        `<li>监听端口 ${PORT}</li>` +
        `<li>时间 ${new Date().toISOString()}</li>` +
        '</ul></body></html>',
    );
});

server.on('error', (err) => {
    log(`server error: ${err && err.message}`);
    process.exitCode = 1;
});

server.listen(PORT, HOST, () => {
    log(`listening on http://${HOST}:${PORT}`);
});

let beats = 0;
const timer = setInterval(() => {
    beats += 1;
    log(`heartbeat ${beats}`);
}, 5000);

function shutdown(signal) {
    log(`received ${signal}, shutting down`);
    clearInterval(timer);
    try {
        server.close(() => process.exit(0));
    } catch (_) {
        process.exit(0);
    }
    setTimeout(() => process.exit(0), 1500);
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
