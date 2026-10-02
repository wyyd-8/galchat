# 手机端浏览器检查

这些用例通过独立的无头 Chrome 运行真实 Vue 页面，拦截 `/api/` 请求并提供固定数据，不访问真实账号、不生成模型回复、不修改业务数据。

先启动 `reka` 的 Vite 开发服务器。环境需提供 Playwright 和 Google Chrome；若 Playwright 不在项目依赖中，可用 `PLAYWRIGHT_MODULE` 指向已有安装的 `playwright/index.mjs`。

```sh
# 在 reka 目录运行；默认访问 http://localhost:5173
node --test test/browser/*.test.mjs

# 自定义已安装的 Playwright、服务地址、截图目录
PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs \
MOBILE_AUDIT_URL=http://localhost:5173 \
MOBILE_AUDIT_SCREENSHOTS=/tmp/galchat-mobile-screenshots \
node --test test/browser/*.test.mjs
```

- `mobile-regressions.test.mjs`：系统返回与草稿保留、模组未保存确认、已关闭群聊操作、弹窗焦点、慢登录请求、键盘视口偏移、好感阈值校验、掷骰快捷导航。
- `mobile-layout.test.mjs`：320、390、430px 三种宽度，各检查 45 个页面/弹窗状态；验证布局边界、输入字号和浏览器运行错误，可导出截图。
- `mobile-fixtures.mjs`：模拟世界、角色、群聊、跑团、模组与账户接口。

软键盘偏移通过 VisualViewport 参数模拟。此检查不等于 iOS Safari / Android 真机验证，亦不覆盖真实服务端写入、邮件、图片上传、模型流式回复和 WebGL 骰子性能。
