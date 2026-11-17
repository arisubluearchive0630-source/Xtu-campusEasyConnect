# 湘潭大学校园网自动登录 App —— 实施计划 v3（锐捷新版认证页 / 已拿到真实 DOM）

> 状态：**待开工**　|　工作目录 C:\autoLoginInternt-Android　|　包名 com.example.android_logininternet
> 依据：html3web 抓包页 + 登录界面截图 + 你复制的真实 outerHTML（html3web/按照大肥鱼的要求复制的文本.html）

## 0. 已确认的事实

| 项 | 结论 |
|---|---|
| 认证入口 | https://zz.xtu.edu.cn/pc/center?flowSessionId=...&nasIp=192.168.251.235&userIp=172.17.125.107&userMac=d494a9fbe8f2 （由网关劫持 HTTP 自动带参跳转） |
| 页面 | Angular SPA（ng-version 12.2.16）+ ng-zorro（ant-*）+ Ionic，标题「连网认证」 |
| 表单 | 顶层 light DOM，**没有 iframe，没有验证码**，表单 id=normalLoginForm，action 指向 zz.xtu.edu.cn/cas-sso/login?service=...（CAS SSO），POST 后回到门户完成网络准入 |
| 结论 | WebView + JS 注入全自动：填账密 → 勾协议 → 点「立即登录」→ 落到 /portal/entry/pc/finish 且外网 204 双判定 |

### 0.1 真实选择器（从你给的 XPath 反推 + DOM 校验）

| 元素 | CSS 选择器 | 你的 XPath |
|---|---|---|
| 账号框 | input#nameInput（name=username，placeholder=请输入账号） | .../form/div[1]/nz-input-group/input |
| 密码框 | input[type=password]（placeholder=请输入密码，注意**无 id**） | .../form/div[2]/nz-input-group/input |
| 协议勾选 | useless-protocol-agreement label.ant-checkbox-wrapper input.ant-checkbox-input | .../useless-protocol-agreement/label/span[1]/input |
| 登录按钮 | button#submitBtn（文案「立即登录」） | .../form/div[5]/div/button |

### 0.2 三个必须处理的细节（否则填了也点不动）

1. **协议默认是未勾选**的：DOM 里那个 checkbox 没有 checked 属性（截图里的蓝勾是你手点的）。脚本必须主动 click，并回读 input.checked 确认。
2. **登录按钮初始带 disabled 类**：账密填好且协议勾上后 Angular 才解除。点击前要轮询等 disabled 消失（上限 3s），仍禁用则退化为 form.requestSubmit() 兜底。
3. **填值必须触发 Angular 的 input 事件**：原生 value setter + dispatch(new Event('input',{bubbles:true}))，再补 change/blur；直接 .value= 不会让表单变 valid，按钮永远是灰的。
4. 页面里还有一个隐藏的 input[name=captcha_code]（value=""），选 checkbox 时要用 .ant-checkbox-input，别误选它。

## 1. 你的三个决定（已并入）

- ✅ 后台常驻守护：**本期不做**，留作后续功能（代码结构预留 Service 位置，不写）。
- ✅ 取消卡片底部「组织」：删掉该显示项，底部只留「运行时长」。
- ✅ 选择器：XPath 已收到，全部落在 light DOM，可直接用；实现里以 CSS 优先 + XPath 兜底。

## 2. 流程（点 ▶ 之后）

1. **校验 Wi-Fi**：SSID 属于 {XTU-STUDENT, XTU-FREE}；不是 → WifiNetworkSuggestion（29+）/ addNetwork+enableNetwork（28）自动连；失败则引导系统 Wi-Fi 设置。
2. **探测**：GET http://connect.rom.miui.com/generate_204（禁跟随重定向）。204 → 已在线直接绿；302 或 HTML 含 zz.xtu.edu.cn → 取入口 URL。
3. **WebView 加载入口 URL**（卡片 2 可见）。
4. **注入脚本**（onPageFinished + 300ms 轮询 15s，等 SPA 渲染完）：
   - 填账号 → input#nameInput；填密码 → input[type=password]（原生 setter + input/change/blur）
   - 勾协议：input.ant-checkbox-input 未勾则 click，回读校验；必要时点 label.protocol__track
   - 等 button#submitBtn 去掉 disabled，再 click；超时则 document.getElementById('normalLoginForm').requestSubmit()
   - 每步经 @JavascriptInterface 的 Bridge.report 回传：filled / agreementChecked / submitted / noForm
5. **判成功（双指标）**：
   - 主：WebView 的 URL 变成 https://zz.xtu.edu.cn/portal/entry/pc/finish（你用真机验证过，是成功页）；
   - 辅：原生每 500ms 探测 generate_204，连续 2 次 204 再确认一次，避免页面到了 finish 但其实没通；
   - 失败：URL 长时间停在登录页 + 页面出现错误文案（如「用户不存在或者密码错误」）→ 状态条显示原文 + 重试按钮。
6. **成功后**：chip 变绿、「运行时长」开始计时、按钮变 ⏹。掉线时（仅当前台）自动回到第 2 步重试；后台守护按你的决定不做。

## 3. 界面（严格 Material 3，深色）

组件与令牌全部用 M3：Scaffold + TopAppBar（可选）、Card / ElevatedCard（不用手绘圆角背景）、FilledIconButton（96dp 圆形主按钮，containerColor = primary）、AssistChip / SuggestionChip 做状态胶囊、Text 用 typography 的 titleLarge / bodyMedium / labelMedium、间距 8dp 栅格、Shape 用 MaterialTheme.shapes（large = 24dp）。颜色走 darkColorScheme(primary = #2F7BFF 系)，不用硬编码十六进制散落各处；不引入自定义 canvas 发光，改为 M3 elevation + tonal surface。

- **卡片 1 状态卡**：状态图标 + 「状态」+ chip（未运行 / 连接中 / 认证中 / 已联网 / 失败）；56dp 圆形图标；SSID 大字 + 「湘潭大学校园网 / 非校园网」徽标；右侧 96dp 蓝色发光圆形播放按钮（▶ / ⏹ / 转圈）；**底部只留「运行时长」**（去掉「组织」列，左对齐）；可选副行显示当前 IP / 上次成功时间。
- **卡片 2 认证页卡**：标题 + 刷新/重试；AndroidView 内 WebView 显示真实认证页；顶部状态条「自动填写中… / 已提交，等待认证 / 认证成功 / 失败：xxx」；未启动时占位提示。
- **设置抽屉（手填，不做引导）**：Material 3 的 OutlinedTextField 填学号 / 密码，Switch 控制「记住密码」「启动时自动连 Wi-Fi」；密码走 EncryptedSharedPreferences。

## 4. 代码结构

- MainActivity.kt（setContent + 运行时权限）
- ui/HomeScreen.kt、ui/components/StatusCard.kt、PortalCard.kt、PlayButton.kt、ui/settings/SettingsSheet.kt
- vm/LoginViewModel.kt（状态机 + 计时 + 重试）
- wifi/WifiSsidReader.kt、wifi/WifiConnector.kt
- net/ConnectivityProbe.kt（204 探测 + 入口 URL 提取）
- portal/PortalAutofill.kt（JS 脚本常量 + 选择器表）
- portal/PortalWebView.kt（WebViewClient + JsBridge + 注入时机）
- data/SettingsRepository.kt

新增依赖：androidx.webkit、okhttp、androidx.security:security-crypto、lifecycle-viewmodel-compose、kotlinx-coroutines-android。

## 5. Manifest

- INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、CHANGE_WIFI_STATE、ACCESS_FINE_LOCATION（≤12 读 SSID）、NEARBY_WIFI_DEVICES（13+，neverForLocation）
- android:usesCleartextTraffic="true"（门户走 http 跳转）
- 不加前台 Service（按你的决定）

## 6. 执行顺序（下令后）

1. 依赖 + 权限 + 深色主题骨架
2. 两张卡片静态 UI（先给你看视觉）
3. SSID 读取 + 204 探测 + 入口 URL 提取（日志验证）
4. ViewModel 状态机 + 计时 + 播放按钮联动
5. WebView 接入 + 自动填充 JS；**离线联调**：把「按照大肥鱼的要求复制的文本.html」放进 assets，用 file:///android_asset/ 跑注入脚本，先验证选择器命中与填值（静态页没有 Angular 运行时，按钮 disabled 不会自动解除，这一步只验选择器与事件派发）
6. 真机联调（本机无 AVD，需要你插手机开 USB 调试）→ assembleDebug → 实测：连 XTU-STUDENT → 点 ▶ → 自动认证成功 → 跑满一轮 204 校验
7. 后续可加（你说要再排）：后台常驻守护、开机自启、Tile 快捷开关、下线按钮、流量显示

## 7. 已确认（无需再问）

1. 成功页 URL = https://zz.xtu.edu.cn/portal/entry/pc/finish;flowParams=undefined;from= —— 判据用「URL 包含 /portal/entry/pc/finish」，后面那串 `;flowParams` 是门户塞的，不做全等匹配。
2. 学号/密码设置里手填，不做首启动引导。
3. 界面严格 Material 3。

---

## 8. 实现进度（v0.1，基本功能已完成）

已落地（包 com.example.android_logininternet，minSdk 28 / targetSdk 36 / compileSdk 37）：

- data/SettingsRepository.kt：EncryptedSharedPreferences 存学号 / 密码 / 自动连接
- wifi/WifiSsidReader.kt：读 SSID + 判断是否 XTU-STUDENT / XTU-FREE
- wifi/WifiConnector.kt：29+ WifiNetworkSuggestion / 28 addNetwork
- net/ConnectivityProbe.kt：generate_204 探测 + 从劫持页抠认证入口
- portal/PortalAutofill.kt：注入脚本（#nameInput、input[type=password]、.ant-checkbox-input、#submitBtn + disabled 轮询 + requestSubmit 兜底）
- portal/PortalWebView.kt：WebView + XtuBridge 回传 + URL 变化上报
- vm/LoginViewModel.kt：状态机（连接 Wi-Fi → 探测 → 加载 → 填充 → 校验 → 成功/失败）+ 计时
- ui/HomeScreen.kt + ui/settings/SettingsDialog.kt：基本功能界面（SSID、状态、启动/停止/重试/设置、WebView 区）
- 构建：assembleDebug 通过；已在 MuMu 模拟器安装启动，无崩溃、权限流程正常

未做（下一步）：
- 视觉细节（Material 3 深色卡片、蓝色圆形播放键、状态 chip）
- 真机校园网联调（用户当前不在验证时段）
- 首次启动按 autoConnect 自动开始
