# 世界与模组 ZIP 归档（后端）

新增 ZIP 接口，原有 JSON 导入、导出和替换接口保持兼容。使用 Java 自带 ZIP 实现，无 Python 服务、无数据库迁移。前端已接入 ZIP 导入导出，手机菜单和桌面工具栏分别适配。

## 接口

所有接口沿用现有登录认证及世界/模组权限。经过部署中的 Nginx 访问时加 `/api` 前缀。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/world/templates/my/{userWorldId}/export-zip` | 导出自己的世界模板 |
| POST | `/world/import-zip` | 导入为新世界模板 |
| PUT | `/world/templates/{id}/replace-zip?confirmLowMatch=false` | 替换世界模板，id 是模板 ID |
| GET | `/coc-modules/{id}/export-zip` | 导出当前用户有读取权限的模组 |
| POST | `/coc-modules/import-zip` | 导入为当前用户拥有的模组 |

导出返回 `application/zip` 下载附件。导入/替换使用 `multipart/form-data`，文件字段为 `file`；响应沿用对应 JSON 接口的 `Result` 数据结构。浏览器用 FormData 时不要手动设置 Content-Type，以便自动生成 boundary。

世界替换返回 `confirmationRequired: true, replaced: false` 时，前端应展示原有匹配统计。用户确认后重新提交同一 ZIP，设置 `confirmLowMatch=true`。首次确认响应不保留新图片，取消无需额外清理请求。

## 包格式

```text
manifest.json
images/
  <image-id>.png
```

`manifest.json` 包含完整的原有归档 DTO：

```json
{
  "packageVersion": 1,
  "type": "world",
  "archive": {
    "formatVersion": 1,
    "world": { "name": "示例", "image": "images/abc.png" },
    "details": [],
    "characters": []
  }
}
```

模组使用 `type: "module"`，`archive` 为原有 CocModuleArchiveDTO（含 formatVersion、module）。packageVersion 与业务 formatVersion 分开校验；这里的示例只说明包结构，业务字段仍须符合原有导入校验。

资源范围：

- 世界：world.image、characters[].image。
- 模组：module.coverUrl、module.materials[].imageUrl；角色卡自由 JSON 内递归识别 image、imageUrl、coverUrl、avatarUrl 字符串字段。
- 同一源图片地址在包内只保存一次。导入生成新的 UUID 文件名，并将全部引用重写为 `/uploads/<UUID>.<扩展名>`。
- 空图片字段不生成资源。不扫描正文 Markdown/HTML 中嵌入的链接，不下载外链；明确图片字段中的非本地地址会导致导出失败。
- 旧 JSON 接口仍保留原有路径语义，不自动迁移图片。

## 校验、限制与事务

ZIP 最大 64 MiB；解压总大小最大 64 MiB；manifest 最大 4 MiB；最多 256 张图片，单张最大 4 MiB。为保证导出的包可重新导入，导出图片总量最多 60 MiB。图片类型和文件头沿用现有 JPG/JPEG/PNG/GIF/WEBP/BMP 校验。

只允许根目录 manifest.json 和 images 下的图片文件；允许可选 images/ 目录条目。拒绝重复文件名、非法路径、未引用图片、缺失图片、版本/类型不匹配及超限文件。不将 ZIP 内的路径直接解压到磁盘，导出拒绝图片符号链接。

全部图片校验完成后才写入 uploads。业务导入在独立数据库事务中执行；只有实际提交成功才保留图片。业务失败、提交失败或替换等待确认时清理本次新文件，不删除已有图片。进程被强制终止或磁盘删除失败不在数据库事务保障范围内，可能留下孤立文件；删除失败会记录为异常。

Spring multipart 文件上限改为 64 MiB，请求上限和 Nginx 上限为 65 MiB（包含 multipart 开销）；普通 `/upload` 仍由图片校验限制为 4 MiB。部署需重启后端并更新 Nginx 配置。

当前实现按请求在内存中处理受限的归档内容，适用于中小型归档；高并发大包场景可后续改为临时文件与流式输出。

## 前端入口

- 桌面：世界库导入、世界设置导出、模板预览中上传替换；模组库顶部导入和模组工具栏导出。
- 手机：世界操作菜单与世界设置中导入、世界设置中导出；模板更多菜单中上传替换；模组列表导入，模组详情更多菜单导出。
- 默认导出 ZIP（包含图片），导入同时支持 ZIP 与旧 JSON。界面展示处理中状态并阻止重复归档请求；文件选择后重置输入框，失败后可重新选择同一文件。
- 模板低匹配确认保留文件和目标模板 ID，确认后重传；取消释放暂存文件。
- 模组导出前先保存可编辑内容；保存失败时停止导出，避免下载旧版本。
