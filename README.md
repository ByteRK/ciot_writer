# CIOT Writer

CIOT Writer 是面向 Android 10（API 29）及以上设备的 Telnet 授权写入工具。它连接局域网中的嵌入式设备和 `MacToAuthCode` 服务：读取设备网卡 MAC，通过服务端授权接口申请授权码，再把模板内容写入设备。

## 烧录流程

1. 在 APP 中配置 `MacToAuthCode` 服务根地址，例如 `http://192.168.1.10:8080`；APP 会请求固定路径 `/api/device/authorize`。
2. 新增或导入产品模板，模板包含产品名、PID、Telnet 网卡名、设备端文件路径、文件内容模板、Telnet 端口和登录凭据。
3. 选择产品模板，输入设备 IP，测试 Telnet 连接（可选）。
4. 烧录时 APP 连接设备、读取网卡 MAC，向服务端发送 `{"pid":"...","mac":"..."}`。服务端按现有逻辑返回已绑定记录，或自动分配新授权码。
5. APP 将 `{{pid}}`、`{{did}}`、`{{license}}` 替换为服务端返回数据，使用设备端 `base64` 命令写入完整文件路径并执行 `sync`。可选择随后自动重启。
6. “同步并重启”按钮单独对当前设备 IP 执行 `sync` 和 `reboot`，不会请求授权或写入文件。

手机需要能同时访问授权服务器和设备 Telnet 端口。设备 Shell 需支持 `cat`、`base64 -d`、`printf`、`sync` 和 `reboot` 命令。

## 产品模板 JSON

APP 导入/导出使用如下结构（版本为 1）。可导入多个产品模板：

```json
{
  "version": 1,
  "templates": [
    {
      "name": "示例产品",
      "pid": "P1001",
      "networkInterface": "eth0",
      "targetPath": "/etc/license.txt",
      "contentTemplate": "pid={{pid}}\ndid={{did}}\nlicense={{license}}\n",
      "port": 23,
      "username": "root",
      "password": ""
    }
  ]
}
```

## 构建

需要 Android SDK（平台 36）和 JDK 17 或更高版本。Windows PowerShell 示例：

```powershell
$env:ANDROID_HOME = "E:\Android\Sdk"
.\gradlew.bat assembleDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 数据与网络

APP 不导入、不保存授权码清单。服务器地址和产品模板保存在 Android 应用私有偏好设置中。模板 JSON 会包括 Telnet 用户名和密码，请按敏感配置文件保管。APP 允许 HTTP 和 HTTPS 服务地址，以兼容仅提供 HTTP 的局域网服务。
