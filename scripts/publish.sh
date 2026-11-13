#!/usr/bin/env bash
# =============================================================================
# scripts/publish.sh —— 企奇商城一次完整发布（版本迭代的唯一入口）
# =============================================================================
#
# 用法
#   scripts/publish.sh --version v0.2.0 [--dry-run] [--skip-docs] [--skip-frontend] [--skip-backend]
#   scripts/publish.sh --rollback v0.1.0 [--dry-run]        # 回滚到指定镜像 tag
#   scripts/publish.sh --version v0.2.0 --dry-run           # 只演练：打印命令 + 校验前置，不动任何东西
#
#   --force-low-mem   逃生开关：跳过"内存/交换区不足"硬门禁（报告里标红，OOM 风险自负）
#
#   环境变量（都有默认值，按需覆盖）
#     HEALTH_TIMEOUT=120     单实例健康轮询上限（秒），超时即判定失败并自动回退该实例
#     HEALTH_INTERVAL=3      轮询间隔（秒）
#     DRAIN_SECONDS=15       docker stop 的优雅退出窗口（让在途请求跑完）
#     FRONTEND_BUILD_MODE=test  vite 构建模式（与 docker-compose.yml 的 BUILD_MODE 一致）
#     EXPECT_MALL=200 EXPECT_ADMIN=401 EXPECT_DOCS=200 EXPECT_HEALTH=200   冒烟期望码
#     MEM_MIN_MB=1200 SWAP_MIN_MB=200   内存/交换区硬门禁阈值（低于就直接 die，除非 --force-low-mem）
#     PROBE_LOGIN=1      是否在每台实例健康之后追加登录探测门禁（=0 关掉，只留浅健康检查，不推荐）
#
# -----------------------------------------------------------------------------
# 一次发布按顺序做这些事（任一步失败即停，并打印回滚提示）
# -----------------------------------------------------------------------------
#   1 preflight          工作区干净 / 分支是 main / 版本号格式 / tag 未被占用 / 依赖齐 / 凭据可读
#   2 tag                本地 git tag -a（不推送）
#   3 tag_push           git push origin <tag>（失败只警告，不阻塞本地 tag、不阻塞发布）
#   4 build_backend      docker build -t qiqi-mall-server:$VER（沿用 compose 的构建上下文）
#   5 rolling_backend    ★滚动重启 server-1 → 健康 → **登录探测** → 网关 reload → server-2 → 健康 → **登录探测** → 网关 reload
#   6 build_frontend     portal-web / admin-web 各建一个 $VER 镜像
#   7 rolling_frontend   两个前端一起换（静态资源，无状态，见下方缓存策略）
#   8 build_docs         node build-material.mjs --mode=prod → dist-prod/
#   8.5 docs_gate        ★文档产物硬门禁（scripts/check-docs-site.mjs 十一项，含「私人文稿零泄漏」+「产物洁净度」）：
#                        不过就 die，一个字节都不上传 —— 详见【十二】
#   9 publish_docs       oss2 同步 dist-prod → oss://qiqi-mall-docs/ + docs/**/*.mmd → _src/ + CDN 刷新（html/md/目录首页 + _vendor/ 的 js/css）
#  10 smoke              线上 5 个探针 + 登录接口（失败只提示回滚，不自动回滚：先让人看一眼）
#                        （登录在第 5 步已经当门禁用过了；这里是从网关再验一遍全链路，不是唯一防线）
#  11 report             版本号 / 三个镜像 tag / tag commit / 耗时 / 每步结果表
#
# =============================================================================
# 设计说明（给未来的自己）
# =============================================================================
#
# 【一】为什么前后端要滚动、而不是"一起停再一起起"
#   后端有两台实例（qiqi-server-1:8082 / qiqi-server-2:8083），网关 upstream 轮询两台。
#   双实例的全部价值就在于：**同一时刻只有一台不在服务**。
#     - 一次只停一台 → 另一台接着扛全部流量 → 用户完全无感（最坏是多一次 TCP 重试）。
#     - 两台一起停 → 网关两个 upstream 全不可用 → 直接 502 断服。这是硬红线。
#   所以本脚本的滚动循环是串行的、带健康门禁的：
#       停 A → 起新镜像 A → 轮询 A 的 /api/health 直到 ok → **才允许**动 B。
#   A 起不来（新镜像炸了/起得比超时还慢）→ 立刻用 A 的旧镜像把它起回来，exit 1，
#   绝不在 A 还病着的时候去动 B（否则就是"两台一起坏"）。
#
# 【二】网关 upstream 会缓存容器 IP —— 所以每次重建实例后必须 nginx -s reload
#   nginx/qiqi.conf 里 upstream 写的是容器名（qiqi-server-1:8082），nginx **只在启动/加载
#   配置时解析一次**并缓存。docker compose 重建容器 → 容器 IP 变了 → 网关还指着旧 IP，
#   表现为 502。qiqi.conf 的注释里也写着这个坑（"先建实例，再重启网关"）。
#   本脚本用 `docker exec qiqi-gateway nginx -s reload`（不是 restart）：master 保持监听、
#   worker 平滑换代，reload 期间旧 worker 还在服务，属于零停机操作，同时会重新解析 upstream。
#   ★ 顺序红线：reload 必须发生在「动下一台之前」。否则停 B 时网关手里的 A 还是旧 IP，
#     两个上游同时不可用 → 断服。代码里这一步的注释写着「不许调换顺序」。
#   另外网关必须把"连不上/超时/上游 502/503"的请求重试到另一台 —— 这是"短暂只有一个
#   upstream 可用"时用户无感的第二个兜底。这条**不能靠 nginx 默认值**（默认是隐式依赖，
#   哪天有人改配置就悄悄没了），所以 nginx/qiqi.conf 里显式写了：
#       proxy_next_upstream error timeout http_502 http_503;
#   并且 preflight 会 grep 这句话，缺了直接拒绝发布（见 check_gateway_retry）。
#   （reload 仍是正解：它让网关重新解析 upstream，重试只是 reload 前后那几百毫秒的保险。
#    另外 nginx 对"已经发出、上游已接单"的非幂等请求（POST）不会重试，滚动期用户最坏
#    看到一次失败，不会断服 —— 所以还是要一台一台滚，见【一】。）
#
# 【三】数据库 migration 只能增不能改
#   判据：**回滚时代码要能跑在老库上**。
#   - 只允许：加表、加列（必须带默认值或可空）、加索引、加新枚举值。
#   - 禁止：改列名/类型、删列、加 NOT NULL 无默认值、收紧约束。
#   原因见【五】。版本回滚时新代码退场、老代码回来；如果 migration 改过老列，
#   老代码就会撞上它不认识的 schema（或写出它读不回的数据），那时**没有退路**。
#   本项目目前没有 Flyway/Liquibase（pom 里没有），迁移是 sql/migrate-*.sql 手工执行：
#     → 手工迁移更要在发布前想清楚"这次能不能回滚"，并把它写进发布说明。
#     → 迁移脚本一律单独执行、先于新镜像上线（新代码要能读老库，老代码也要能读新库）。
#
# 【四】前端缓存策略：index.html 不缓存、带 hash 的 js/css 长缓存
#   admin-web/portal-web 的 nginx 模板对 /assets/（vite 产物，文件名带 content hash）
#   设 30d + immutable；index.html 走 location / 的 try_files，源站没给强缓存头。
#   含义：**发版实际变化的就是 index.html 这一个文件**（它引用新的 hash 文件名）。
#   所以前端可以"两个一起换"——两个前端是彼此独立的服务（5174 / 5176），
#   换一个不影响另一个；单实例容器重建会有 1~2 秒空窗，但静态页本身就是秒级重启。
#   （如果哪天前端也要做到零停机，办法是给每个前端加第二个实例 + 网关轮询，同一个套路。）
#
# 【五】回滚边界：镜像能回滚，数据库不能
#   `--rollback vX` 只能把**镜像**退回去（容器重建 = 秒级），
#   已经执行过的 migration **回不去了**（数据被改写/新列已被写入，回滚 SQL 未必可逆）。
#   推论：迁移必须向前兼容（【三】）——让 N 版代码也能跑在 N+1 版的库上。
#   文档站同理：dist-prod 是整目录覆盖上去的，没有历史版本；真要回退文档，
#   只能 checkout 到对应版本的 docs 快照重新 build（docs/ 不进 git，见【六】）。
#
# 【六】文档缓存策略：html/md 不缓存、图/视频 30 天 —— 所以发版只刷 html/md
#   CDN（docs.rose7.cn）缓存规则与 cdn.rose7.cn 一致：html/htm/md → TTL 0；
#   svg/png/jpg/webp/gif/css/js/woff2/mp4 → 30 天。所以一次文档发版，需要让 CDN
#   立刻失效的只有 html 与 md（docsify 是运行时 fetch .md 的 SPA），图/视频走 URL
#   不变 + 30 天缓存，不必刷（刷了反而把 30 天缓存打成 0，白烧回源）。
#   刷新用 RefreshObjectCaches(File) 精确到文件，不做目录刷新/全站刷新。
#   ★ docs/ 不进 git（.gitignore 里整体忽略），所以 docs/**/*.mmd 源码**唯一**的备份
#     就是 oss://qiqi-mall-docs/_src/。同步逻辑删多余对象时显式跳过 _src/ 前缀，
#     千万别"顺手清理"掉——那是不可恢复的源码。
#   ★ _vendor/ 例外：docsify 的搜索/缩放/语法高亮/主题（_vendor/*.js、*.css）文件名里
#     **没有 content hash**（docsify.min.js 永远叫 docsify.min.js），URL 不变 + 30 天缓存
#     ⇒ 改了样式/升级了插件而不刷 CDN，用户那边永远看不到新版（要等 30 天）。
#     所以 CDN 刷新清单除了 html/md/目录首页，还必须追加 dist-prod/_vendor/ 下所有 js/css
#     （运行时扫目录，不硬编码文件名，将来加插件自动跟上）。
#
# 【七】构建方式沿用仓库现有约定，不在宿主机跑 npm/mvn
#   qiqi-mall-server/Dockerfile 是多阶段（maven:3.9-eclipse-temurin-21 → temurin jre-alpine），
#   portal-web/admin-web 也是多阶段（node:22-alpine 里 npm ci + vite build → nginx:alpine）。
#   所以"前端构建"= docker build 一步，不在宿主机 `npm ci && npm run build`：
#   宿主机不落 node_modules/target，也不和正在跑的其它智能体抢内存。
#   ★ compose 的 service 配置（端口/env/网络/healthcheck）是唯一事实来源：
#     发布只是把 image 换成 $VER 版本（通过临时 override 文件注入），
#     **不改 docker-compose.yml 一个字**。
#   ★ 所有 compose 调用都必须 cd 到仓库根：.env 里有 OSS_ACCESS_KEY/OSS_SECRET_KEY，
#     compose 只从 project dir 的 .env 插值；漏了它后端会因 require-credentials: true 起不来。
#
# 【八】凭据
#   OSS/CDN：/root/.aliyun-qiqi/credentials.env 的 ALI_AK / ALI_SK，**只读不打印**，
#            所有回显走 redact()。admin 口令：/root/.qiqi-secrets/admin.txt（裸口令，无 key）。
#            两者都不进 git、不进 argv（避免 ps 泄漏）、不进日志。
#
# 【九】已知风险（脚本没法替你消掉的）
#   - /api/health 是 HealthController 的浅健康检查（只回 status/env/service），
#     **不验证 DB/Redis/OSS**：容器"健康"不等于业务可用。所以滚动时每台实例健康之后
#     还要过一道登录探测（见【十】）——那才是"这台真能查库"的证据。
#   - 宿主机交换区接近耗尽 + 多智能体并行时，maven 构建可能被 OOM kill。
#     这条从"警告"升级成**硬门禁**：SwapFree < 200MB 或 MemAvailable < 1200MB 直接 die。
#     理由：maven 多阶段构建 + 两个 JVM 冷启动会同时要内存，宿主机彻底没 swap 时内核
#     OOM killer 挑的是"最大的进程"——很可能是**正在服务的另一台实例或别人的容器**，
#     那不是"发布失败"，是直接把线上打断。逃生开关 --force-low-mem（报告里标红）。
#   - 冒烟用公网域名，依赖 ECS Caddy → tailscale → 本机这条链路；
#     链路问题时冒烟会红，但那不是本次发布的锅（看 smoke 的失败明细再判断）。
#
# 【十】登录探测 = 发布门禁（不是最后一步的冒烟）
#   上一版把登录冒烟放在最后（smoke），失败了只报警不回滚 —— 太晚：那时两台实例都已经
#   切完、新版本已经在扛流量了。现在提前到滚动循环里，**每台实例** /api/health 通过之后
#   立刻补一次登录探测，过了才允许 reload 网关把它放进 upstream；不通过 = 这台不健康 →
#   直接走 rollback_one_instance（回旧镜像），绝不继续动另一台。
#   为什么必须是登录而不是 /api/health：登录必查 admin 表（AdminService.getOne），
#   能回答"这台真的连得上库吗"；/api/health 只说明 JVM 活着。
#
#   ★ 验证码限流（这里最容易搞出事故）：
#     CaptchaStore 按 **用户名** 记失败次数（key=captcha:fail:{username}，5 分钟窗口），
#     累计 5 次之后同一个用户名登录就要求图形验证码 —— 脚本不做图形码，等于把 admin 锁死。
#     所以家规是：
#       1) 正例固定只用 admin 一个账号，且**只打 1 次**，绝不重试；
#       2) 打之前先 GET /admin/auth/captcha-required?username=admin（只读，不计数）；
#          已经要求验证码就干脆不打 admin，直接走负例；
#       3) 失败后用 probe_<epoch> 这种**一次性用户名**做负例：库里当然没有这个人，必然
#          401「用户名或密码错误」——而这条 401 本身就证明"这台查过库、在正常处理请求"。
#          负例只用来区分「口令错/账号被禁」（有业务响应）和「服务没起来」（5xx/连不上）。
#          永远不要拿 admin 再试第二次去区分这两件事。
#       4) 判定只看 **HTTP 状态码**，不看响应体：busybox wget 遇到 4xx/5xx 会把 body 丢掉，
#          只剩一行 "wget: server returned error"。所以 200+accessToken=通；400/401=服务在
#          正常处理业务请求（结合 2) 已排除验证码 ⇒ 它查过库）；5xx/超时/Connection refused/
#          没有状态行=没起来。
#
#   ★ 口令传递：只从 /root/.qiqi-secrets/admin.txt 读进 shell 变量 → 走 stdin 进 python 拼
#     JSON → 再走 stdin 进容器内的临时文件（umask 077，用完即删）→ wget --post-file。
#     全程不进 argv（宿主机和容器的 ps 都看不到）、不 echo、不写日志；响应里的 accessToken
#     也只在断言 "grep 到 accessToken" 时用，从不打印。
#
# 【十一】CDN 刷新的 ObjectPath 必须 percent-encode —— 否则「报告成功但线上不生效」
#   阿里云 CDN 的 RefreshObjectCaches 对 ObjectPath 里的中文**不做容错**，而且是**静默失败**：
#     - 原样提交中文路径（如 https://docs.rose7.cn/material/diagrams/02-关键机制/6-并发支付.md）
#       → 接口 200 + RefreshTaskId；DescribeRefreshTasks 里 Status=Complete / Process=100%，
#         **清单里明确列出了那个对象**（看起来完全成功）
#       → 但线上仍是 X-Cache: HIT、Age: 7948，返回的还是旧内容
#     - 同一条路径 quote(..., safe="/") 编码后提交 → 90 秒内 X-Cache: MISS 回源，拿到新内容
#   机制：CDN 的缓存 key 是**编码后的 URL**（浏览器/curl 发的也是编码串）。原样中文的
#   ObjectPath 跟这个 key 对不上 ⇒ 任务"成功"完成，但一份缓存都没被失效。文档站 98 条
#   中文 md/svg 路径全都踩在这上面 ⇒ "每次发版都报告成功、线上却一直是旧内容"。
#   所以：① 所有 ObjectPath 一律过 publish_docs 里的 encode_object_path()（safe="/"，
#           幂等：已编码的输入不会二次编码）；
#         ② 提交后**必须回读校验**（抽 5 条 HEAD，看 X-Cache 从 HIT 变 MISS/EXPIRED）——
#            这个接口错了也返回成功，没有回读就等于没有证据。
#
# 【十二】文档产物硬门禁 = 上传 OSS 之前必须整条 PASS（check-docs-site.mjs 十一项）
#   历史教训：口语稿（作者口述稿）和 build-material.mjs 里 PRIVATE_DOCS 那 12 篇私人笔记
#   属于「私人文稿」，**只能出现在 dev 站**。prod 侧原先只靠 build-material.mjs 的实现细节
#   保证干净（`if (MODE !== 'dev') return null` 不读口语稿、prod 分支不拷那 12 篇）——
#   那是代码层约定，**没有任何外部门禁**：谁改了那个 if、加一个新素材目录、或手工往 dist-prod
#   塞一个文件，没有任何东西会拦下来，而它会被直接同步上 OSS。
#   为什么后果特别重：html/md 是 no-cache（发版能刷），但 svg/mp4/_vendor 下的 js/css 是
#   **30 天缓存**（见【六】）。私人笔记/口语稿一旦传上去，就算事后删了源对象、刷了 CDN，
#   边缘节点上那份字节也要 30 天才会自然过期 —— 公网上撤不干净。
#   所以：build_docs 之后、publish_docs 之前加一道 check_docs_gate：
#     node scripts/check-docs-site.mjs --root dist-prod --skip-http
#   十一项（页面数 / 图引用 / 视频引用 / 图-src 配对 / 场景齐全 / 内网信息 / SVG 硬指标 /
#   图内禁词 / 侧边栏 / **⑩ 私人文稿零泄漏** / **⑪ 产物洁净度**）里有任何一项不过 → die，
#   **一个字节都不上传**。
#   上传前只做静态检查（--skip-http）：此刻公网还是上一版，探测公网既慢又没有意义。
#   ⑩ 的口径是「按字节判」，不看代码怎么写的：产物里出现 ①「口语稿」/「spoken」文件名、
#   ② 口语稿特征词（一分钟口语稿 / 「可直接念」句式 / spoken-script / 口语稿）、
#   ③ PRIVATE_DOCS 那 12 篇的原文件、页面链接或标题 —— 任意一处命中即 FAIL。
#   ⑪ 管「根本不该出现在产物里」的东西（不需要逐篇登记）：私密文件名（简历/口述周边/复盘/-笔记/JD、
#   .env/credentials/*.pem/id_rsa/.git/.DS_Store/*~/ *.bak、node_modules、*.webm/trace.zip/
#   playwright-report、_deploy/_evidence）+ 正文里的内网 IP / 绝对路径 /root/ /tmp/ / 容器名 /
#   docker compose。理由：这些原先全靠 build-material「不拷」这个约定，PRIVATE_DOCS 又是逐篇登记，
#   新加一篇私人笔记忘了登记就漏 —— ⑪ 是那条路的兜底。
#   ⚠ 门禁本身被绕过的方式只剩「改脚本」，所以 check-docs-site.mjs 的 --private-ok（dev 站豁免）
#     绝不能出现在这里的命令行里。
# =============================================================================

set -uo pipefail

# ------------------------------------------------------------------ 常量/默认值
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SERVER_REPO="qiqi-mall-server"
PORTAL_REPO="qiqi-portal-web"
ADMIN_REPO="qiqi-admin-web"
# ★ 坑：前端**镜像名 ≠ 源码目录名**，必须分开定义（目录名与 docker-compose.yml 的
#   build.context: ./portal-web / ./admin-web 一致）。build_frontend 曾直接用镜像名
#   拼构建上下文，真实发布时 `unable to prepare context: path .../qiqi-portal-web
#   not found` 直接中止（dry-run 只打印命令，所以演练看不出来）。
#   后端没暴露这个坑纯属巧合：目录名恰好等于镜像名 qiqi-mall-server。
PORTAL_DIR="portal-web"
ADMIN_DIR="admin-web"

C_SERVER1="qiqi-server-1"; C_SERVER2="qiqi-server-2"
C_PORTAL="qiqi-portal-web"; C_ADMIN="qiqi-admin-web"
C_GATEWAY="qiqi-gateway";   C_MYSQL="qiqi-mysql"
P_SERVER1=8082; P_SERVER2=8083

COMPOSE_BASE="$REPO_ROOT/docker-compose.yml"
HEALTH_PATH="/api/health"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-3}"
DRAIN_SECONDS="${DRAIN_SECONDS:-15}"
FRONTEND_BUILD_MODE="${FRONTEND_BUILD_MODE:-test}"
# 内存/交换区硬门禁（见脚本头【九】）。可覆盖，但 --force-low-mem 才是正经逃生门：
# 环境变量适合"这台机器确实小一点"的长期调整，command-line 开关适合"我知道现在很紧，就发这一次"。
MEM_MIN_MB="${MEM_MIN_MB:-1200}"     # MemAvailable 低于它就 die
SWAP_MIN_MB="${SWAP_MIN_MB:-200}"    # SwapFree 低于它就 die
# 每台实例健康之后的登录探测门禁（见脚本头【十】）；=0 只留浅健康检查（不推荐）
PROBE_LOGIN="${PROBE_LOGIN:-1}"

DOCS_SRC_DIR="/root/docsify"
DOCS_BUILD="$DOCS_SRC_DIR/build-material.mjs"
DOCS_DIST="$DOCS_SRC_DIR/dist-prod"
DOCS_MMD_ROOT="$REPO_ROOT/docs"
DOCS_BUCKET="qiqi-mall-docs"
DOCS_DOMAIN="docs.rose7.cn"
OSS_ENDPOINT="oss-cn-beijing.aliyuncs.com"
CDN_REGION="cn-hangzhou"

CRED_FILE="/root/.aliyun-qiqi/credentials.env"
ADMIN_SECRET_FILE="/root/.qiqi-secrets/admin.txt"

EXPECT_MALL="${EXPECT_MALL:-200}"
EXPECT_ADMIN="${EXPECT_ADMIN:-401}"   # admin.rose7.cn 在 ECS Caddy 上挂了 basic auth
EXPECT_DOCS="${EXPECT_DOCS:-200}"
EXPECT_HEALTH="${EXPECT_HEALTH:-200}"

# ------------------------------------------------------------------ 参数
MODE="publish"        # publish | rollback
VER=""
DRY_RUN=0
SKIP_DOCS=0
SKIP_FRONTEND=0
SKIP_BACKEND=0
FORCE_LOW_MEM=0       # --force-low-mem：跳过内存/交换区硬门禁（报告里标红）
FORCE_LOW_MEM_USED=0  # 记录"门禁真的被跳过了"，供收尾报告标红

# ------------------------------------------------------------------ 运行期状态
T0=0
TAG_COMMIT=""
PREV_SERVER_IMG="qiqi-mall-server:test"   # 滚动失败时回退用（真实值在 preflight 里从容器读）
STEP_NAME=""; STEP_T0=0; STEP_OPEN=0
declare -a R_NAME=() R_STAT=() R_SEC=()
OVERRIDE_DIR=""; OVERRIDE_FILE=""
IMG_SERVER1=""; IMG_SERVER2=""; IMG_PORTAL=""; IMG_ADMIN=""

if [ -t 1 ]; then
  C_B=$'\033[1m'; C_R=$'\033[31m'; C_G=$'\033[32m'; C_Y=$'\033[33m'; C_D=$'\033[2m'; C_0=$'\033[0m'
else
  C_B=""; C_R=""; C_G=""; C_Y=""; C_D=""; C_0=""
fi

# ------------------------------------------------------------------ 输出helpers
log()  { printf '%s\n' "$*"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '%s⚠ %s%s\n' "$C_Y" "$*" "$C_0" >&2; }
err()  { printf '%s✗ %s%s\n' "$C_R" "$*" "$C_0" >&2; }

step_begin() {
  STEP_NAME="$1"; STEP_T0=$SECONDS; STEP_OPEN=1
  printf '\n%s▶ %s%s\n' "$C_B" "$1" "$C_0"
}
step_end() { # $1 = ok|fail|skip|dry
  [ "$STEP_OPEN" -eq 1 ] || return 0
  R_NAME+=("$STEP_NAME"); R_STAT+=("$1"); R_SEC+=($(( SECONDS - STEP_T0 )))
  STEP_OPEN=0
}

# 执行命令；dry-run 只打印。只用于"单条、无重定向、无管道"的命令。
exe() {
  printf '    %s$ %s%s\n' "$C_D" "$*" "$C_0"
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  "$@"
}
# 同上，但把命令自身的输出吞掉（docker stop 会回显容器名，报告里不需要）
exe_quiet() {
  printf '    %s$ %s%s\n' "$C_D" "$*" "$C_0"
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  "$@" >/dev/null 2>&1
}
# 复合命令（管道/重定向）在 dry-run 下只打印这一行，不执行。
drynote() { printf '    %s$ %s%s\n' "$C_D" "$*" "$C_0"; }

die() {  # 失败即停：打印回滚提示；EXIT trap 负责收尾表格
  err "$*"
  step_end fail
  exit 1
}

usage() {
  sed -n '6,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# ------------------------------------------------------------------ 收尾报告
print_report() {
  local rc=$?
  [ "${#R_NAME[@]}" -gt 0 ] || return 0
  local total=$(( SECONDS - T0 ))
  printf '\n%s┌─ 发布步骤结果 ────────────────────────────────────────────┐%s\n' "$C_B" "$C_0"
  local i
  for i in "${!R_NAME[@]}"; do
    local st="${R_STAT[$i]}" mark
    case "$st" in
      ok)   mark="${C_G}OK  ${C_0}" ;;
      fail) mark="${C_R}FAIL${C_0}" ;;
      skip) mark="${C_Y}SKIP${C_0}" ;;
      dry)  mark="${C_Y}DRY ${C_0}" ;;
      *)    mark="$st" ;;
    esac
    printf '  %2d. %-28s %s  %3ds\n' "$((i+1))" "${R_NAME[$i]}" "$mark" "${R_SEC[$i]}"
  done
  printf '%s└───────────────────────────────────────────────────────────┘%s\n' "$C_B" "$C_0"
  printf '  总耗时：%ds（%dm%ds）\n' "$total" $(( total/60 )) $(( total%60 ))
  # 成功就清掉临时 override 目录；失败保留，方便对着现场复现
  if [ "$rc" -eq 0 ] && [ -n "$OVERRIDE_DIR" ] && [ -d "$OVERRIDE_DIR" ]; then
    rm -rf "$OVERRIDE_DIR"
  fi

  if [ "$rc" -ne 0 ]; then
    printf '\n%s%s 发布中断 —— 手动回滚提示 %s\n' "$C_B" "$C_R" "$C_0"
    if [ "$MODE" = "rollback" ]; then
      printf '  本次是回滚任务本身失败，先看容器状态：\n'
      printf '    docker ps -a --filter name=qiqi-\n'
      printf '    docker logs --tail 100 %s\n' "$C_SERVER1"
    else
      local prevtag="${PREV_SERVER_IMG#*:}"
      printf '  发布前线上镜像是：%s\n' "$PREV_SERVER_IMG"
      if [[ "$prevtag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        printf '  回滚到它（后端滚动、不停机）：scripts/publish.sh --rollback %s\n' "$prevtag"
      else
        printf '  它不是版本 tag（%s），回滚方式：把容器切回该镜像\n' "$prevtag"
        printf '    docker compose up -d --no-deps server-1 server-2   # base 文件里 image 仍是它\n'
        printf '  或 scripts/publish.sh --rollback <某个旧版本 tag>\n'
      fi
      printf '  只想捞某一台的现场：\n'
      printf '    docker logs --tail 200 %s\n' "$C_SERVER1"
      printf '    docker logs --tail 200 %s\n' "$C_SERVER2"
    fi
    printf '  注意：镜像能回滚，数据库 migration 不能（见脚本头【五】）。\n'
  fi
}
trap print_report EXIT

# ------------------------------------------------------------------ 0. 参数解析
parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --version)     VER="${2:-}"; shift 2 ;;
      --version=*)   VER="${1#*=}"; shift ;;
      --rollback)    MODE="rollback"; VER="${2:-}"; shift 2 ;;
      --rollback=*)  MODE="rollback"; VER="${1#*=}"; shift ;;
      --dry-run)     DRY_RUN=1; shift ;;
      --skip-docs)     SKIP_DOCS=1; shift ;;
      --skip-frontend) SKIP_FRONTEND=1; shift ;;
      --skip-backend)  SKIP_BACKEND=1; shift ;;
      --force-low-mem) FORCE_LOW_MEM=1; shift ;;
      -h|--help)     usage 0 ;;
      *) err "未知参数：$1"; usage 1 ;;
    esac
  done
  [ -n "$VER" ] || { err "缺少 --version 或 --rollback"; usage 1; }
}

# ------------------------------------------------------------------ 1. 前置检查
check_version_format() {
  local strict='^v[0-9]+\.[0-9]+\.[0-9]+$'
  local relaxed='^v[0-9]+\.[0-9]+\.[0-9]+([-+.][A-Za-z0-9._-]+)?$'
  if [[ "$VER" =~ $strict ]]; then
    return 0
  fi
  if [ "$MODE" = "rollback" ] && docker image inspect "$SERVER_REPO:$VER" >/dev/null 2>&1; then
    # 紧急通道：回滚允许直接用任意本地镜像 tag（如 :test），不强制 vX.Y.Z
    warn "回滚目标 $VER 不是 v<major>.<minor>.<patch> 形式，但本地有 $SERVER_REPO:$VER → 按镜像 tag 原样使用"
    return 0
  fi
  if [ "$DRY_RUN" -eq 1 ] && [[ "$VER" =~ $relaxed ]]; then
    # 演练允许 v0.0.1-dryrun 这类明显是假的版本号（否则没法拿一个"不会被占用"的名字演练）
    warn "版本号 $VER 不是严格的 v<major>.<minor>.<patch>；dry-run 放宽放行（真实发布会拒绝）"
    return 0
  fi
  err "版本号格式非法：$VER（要求 v<major>.<minor>.<patch>，例：v0.2.0）"
  return 1
}

check_git_clean() {
  local dirty
  dirty="$(git -C "$REPO_ROOT" status --porcelain)"
  if [ -z "$dirty" ]; then
    info "工作区干净 ✓"
    return 0
  fi
  err "工作区不干净，拒绝发布。未提交的改动："
  printf '%s\n' "$dirty" | sed 's/^/      /' >&2
  info "要么先提交，要么 git stash，再重跑。"
  return 1
}

check_branch() {
  local br
  br="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
  if [ "$br" = "main" ]; then
    info "当前分支 main ✓"
    return 0
  fi
  err "当前分支是 $br，不是 main；发布只允许从 main 出"
  return 1
}

check_tag_free() {
  if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$VER" >/dev/null 2>&1; then
    err "tag $VER 已存在（git tag -l '$VER'），换一个版本号"
    return 1
  fi
  info "tag $VER 未被占用 ✓"
  return 0
}

check_deps() {
  local missing=0 c
  for c in docker git curl python3 node; do
    command -v "$c" >/dev/null 2>&1 || { err "缺少依赖命令：$c"; missing=1; }
  done
  docker compose version >/dev/null 2>&1 || { err "缺少 docker compose 插件"; missing=1; }
  [ "$missing" -eq 0 ] || return 1
  # oss2（上传）+ aliyunsdkcore/aliyunsdkcdn（CDN 刷新）
  local pycheck
  pycheck="$(python3 - <<'PY' 2>&1
mods = []
for m in ("oss2", "aliyunsdkcore", "aliyunsdkcdn"):
    try:
        __import__(m); mods.append(m + "=ok")
    except Exception:
        mods.append(m + "=MISSING")
print(" ".join(mods))
PY
)"
  info "python 模块：$pycheck"
  case "$pycheck" in *MISSING*) err "python 依赖不全（pip3 install oss2 aliyun-python-sdk-core aliyun-python-sdk-cdn）"; return 1 ;; esac
  info "依赖检查通过 ✓（docker / docker compose / git / curl / python3+oss2+sdk / node）"
  return 0
}

check_credentials() {
  [ -r "$CRED_FILE" ] || { err "读不到凭据文件 $CRED_FILE"; return 1; }
  # 只断言两个键存在，绝不回显值
  if python3 - "$CRED_FILE" <<'PY' >/dev/null 2>&1
import sys
ak = sk = None
for line in open(sys.argv[1], encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    v = v.strip().strip('"').strip("'")
    if k.strip() == "ALI_AK" and v: ak = v
    if k.strip() == "ALI_SK" and v: sk = v
sys.exit(0 if (ak and sk) else 1)
PY
  then
    info "OSS/CDN 凭据：$CRED_FILE 含 ALI_AK + ALI_SK ✓（未回显）"
  else
    err "凭据文件缺 ALI_AK 或 ALI_SK（$CRED_FILE）"
    return 1
  fi
  if [ -r "$ADMIN_SECRET_FILE" ]; then
    info "admin 口令文件可读 ✓（未回显）"
  else
    warn "读不到 $ADMIN_SECRET_FILE：冒烟里的登录用例会跳过"
  fi
  return 0
}

# 内存/交换区：**硬门禁**，不是提示（上一版只是 <1.5GB 时 warning，等于把判断推给人）
# 为什么必须拦：maven 多阶段构建 + 两个 JVM 冷启动会同时吃内存；宿主机真的没内存没 swap
# 的时候，内核 OOM killer 挑的是"最大的进程"——大概率是**正在服务的另一台实例**或别人的
# 容器。那不是"这次构建失败"，是直接把线上打断，比中止发布严重得多。所以：
#   SwapFree < SWAP_MIN_MB  或  MemAvailable < MEM_MIN_MB  →  die
# 逃生开关 --force-low-mem（真的就这一次、且知道风险时用；报告里标红）。
check_resources() {
  local avail swap_free why=""
  avail="$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)"
  swap_free="$(awk '/^SwapFree:/{print int($2/1024)}' /proc/meminfo)"
  avail="${avail:-0}"; swap_free="${swap_free:-0}"
  info "可用内存 ${avail}MB（门禁 ≥${MEM_MIN_MB}）/ 空闲 swap ${swap_free}MB（门禁 ≥${SWAP_MIN_MB}）"

  if [ "$swap_free" -lt "$SWAP_MIN_MB" ]; then
    why="SwapFree ${swap_free}MB < ${SWAP_MIN_MB}MB"
  fi
  if [ "$avail" -lt "$MEM_MIN_MB" ]; then
    why="${why:+$why；}MemAvailable ${avail}MB < ${MEM_MIN_MB}MB"
  fi
  [ -n "$why" ] || { info "内存/交换区门禁通过 ✓"; return 0; }

  if [ "$FORCE_LOW_MEM" -eq 1 ]; then
    # 标红是刻意的：这个开关用过一次就要在报告里留痕，事后复盘能对上是哪次发布冒的险
    printf '%s⚠ 内存/交换区门禁被 --force-low-mem 强制跳过：%s（OOM 风险自负）%s\n' \
      "$C_R" "$why" "$C_0" >&2
    FORCE_LOW_MEM_USED=1
    return 0
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    # 演练只报不拦：内存紧张的机器也得能演练，但话要说死，别让人以为真实发布会照样放行
    err "内存/交换区不足（硬门禁不通过）：$why"
    warn "dry-run：只报不拦，继续演练 —— 真实发布会在这里直接 die（加 --force-low-mem 才是唯一的绕过方式）"
    return 1
  fi
  die "内存/交换区不足，拒绝发布：$why —— maven 构建 + JVM 冷启动会 OOM，可能连带打断线上另一台（确认要发就加 --force-low-mem）"
}

# 零停机依赖网关显式重试：nginx/qiqi.conf 里必须有 proxy_next_upstream
# （滚动时一台实例正在重启，网关手里那个 upstream 会 error/timeout/502/503，靠这条重试到另一台。
#  它以前是 nginx 的**默认值**，属于隐式依赖 —— 谁改一行配置就悄悄没了，所以改成显式 + 每次发布校验。）
check_gateway_retry() {
  local f="$REPO_ROOT/nginx/qiqi.conf"
  if [ ! -f "$f" ]; then
    err "网关配置缺失：$f"
    return 1
  fi
  if grep -q 'proxy_next_upstream' "$f"; then
    local line
    line="$(grep -m1 'proxy_next_upstream' "$f" | sed 's/^[[:space:]]*//')"
    info "网关显式重试 ✓（$line）"
    return 0
  fi
  err "nginx/qiqi.conf 里 grep 不到 proxy_next_upstream —— 零停机依赖这条重试，不能删"
  info "滚动期被停掉的那台实例会让网关拿到 error/timeout/502/503，没有重试就是用户可见的 502。"
  info "补回这一行（server 块里，upstream 之后）："
  info "  proxy_next_upstream error timeout http_502 http_503;"
  return 1
}

check_build_inputs() {
  local ok=0 f
  for f in \
    "$REPO_ROOT/qiqi-mall-server/Dockerfile" \
    "$REPO_ROOT/qiqi-mall-server/docker/settings.xml" \
    "$REPO_ROOT/qiqi-mall-server/pom.xml" \
    "$REPO_ROOT/portal-web/Dockerfile" "$REPO_ROOT/admin-web/Dockerfile"
  do
    [ -f "$f" ] || { err "构建输入缺失：$f"; ok=1; }
  done
  if [ "$SKIP_DOCS" -eq 0 ]; then
    [ -f "$DOCS_BUILD" ] || { err "构建脚本缺失：$DOCS_BUILD"; ok=1; }
    [ -d "$DOCS_MMD_ROOT" ] || { err "mmd 源码目录缺失：$DOCS_MMD_ROOT"; ok=1; }
  fi
  [ "$ok" -eq 0 ] && info "构建输入齐全 ✓"
  return "$ok"
}

record_prev_images() {
  local img
  img="$(docker inspect -f '{{.Config.Image}}' "$C_SERVER1" 2>/dev/null || true)"
  [ -n "$img" ] || img="$(docker inspect -f '{{.Config.Image}}' "$C_SERVER2" 2>/dev/null || true)"
  # 只取本仓库的镜像名，避免拿到 digest/其它仓库的东西
  case "$img" in
    "$SERVER_REPO":*) PREV_SERVER_IMG="$img" ;;
    *) PREV_SERVER_IMG="$SERVER_REPO:test" ;;
  esac
  info "当前线上后端镜像（回退目标）：$PREV_SERVER_IMG"
}

preflight() {
  local bad=0
  step_begin "前置检查"

  check_version_format || bad=1
  if [ "$MODE" = "publish" ]; then
    # 发布：必须从干净的 main 出，且 tag 未被占用
    check_branch    || bad=1
    check_git_clean || bad=1
    check_tag_free  || bad=1
    check_build_inputs || bad=1
    [ "$SKIP_DOCS" -eq 1 ] || check_credentials || bad=1
  else
    # 回滚是紧急操作：不要求工作区干净、不要求 main（本地镜像 tag 存在即可）
    info "回滚模式：跳过 git 洁净/分支检查（紧急通道）"
  fi
  if [ "$MODE" = "rollback" ]; then
    # 回滚：目标镜像必须本地存在（这套部署没有 registry，镜像是本机的）
    local t
    for t in "$SERVER_REPO:$VER" "$PORTAL_REPO:$VER" "$ADMIN_REPO:$VER"; do
      if docker image inspect "$t" >/dev/null 2>&1; then
        info "回滚目标镜像存在：$t ✓"
      else
        err "回滚目标镜像不存在：$t（本机没有 registry；只能用本机留着的 tag）"
        bad=1
      fi
    done
  fi
  check_deps || bad=1
  check_gateway_retry || bad=1     # 零停机前提：网关必须显式声明重试
  check_resources || bad=1         # 硬门禁：内存/交换区不足直接 die（--force-low-mem 可绕过）
  record_prev_images

  if [ "$bad" -ne 0 ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
      # 演练的价值就在于把流程走完给人看：前置硬失败也只警告，不中断
      warn "dry-run：前置检查有失败项，继续演练（真实发布会在这里退出 1）"
      step_end dry
      return 0
    fi
    die "前置检查未通过，发布中止（什么都没做：没有 tag、没有构建、没有重启）"
  fi

  if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else step_end ok; fi
}

# ------------------------------------------------------------------ 2. 打 tag
do_tag() {
  step_begin "打本地 tag"
  local msg="release $VER — 后端 qiqi-mall-server:$VER / 前端 qiqi-portal-web:$VER, qiqi-admin-web:$VER（scripts/publish.sh）"
  exe git -C "$REPO_ROOT" tag -a "$VER" -m "$msg"
  if [ "$DRY_RUN" -eq 0 ]; then
    TAG_COMMIT="$(git -C "$REPO_ROOT" rev-list -n1 "$VER")"
    info "tag $VER → $TAG_COMMIT"
    step_end ok
  else
    TAG_COMMIT="（dry-run 未打 tag；当前 HEAD=$(git -C "$REPO_ROOT" rev-parse --short HEAD)）"
    step_end dry
  fi
}

# ------------------------------------------------------------------ 3. 推 tag（失败不阻塞）
do_push_tag() {
  step_begin "推送 tag"
  if git -C "$REPO_ROOT" remote get-url origin >/dev/null 2>&1; then
    printf '    %s$ git push origin %s%s\n' "$C_D" "$VER" "$C_0"
    if [ "$DRY_RUN" -eq 1 ]; then
      step_end dry; return 0
    fi
    if git -C "$REPO_ROOT" push origin "$VER" 2>&1 | sed 's/^/      /'; then
      info "tag 已推送 ✓"
      step_end ok
    else
      warn "推送 tag 失败（不回滚本地 tag，也不阻塞发布）：手动执行 git push origin $VER"
      step_end skip
    fi
  else
    info "没有 origin 远端，跳过推送"
    step_end skip
  fi
}

# ------------------------------------------------------------------ compose override
# 关键：不改 docker-compose.yml。发布用 -f base -f override 叠加，只覆盖 image 字段。
write_override() {
  mkdir -p "$OVERRIDE_DIR"
  {
    echo "# 自动生成（scripts/publish.sh）——只覆盖 image，不改 base 的任何配置"
    echo "services:"
    [ -n "$IMG_SERVER1" ] && echo "  server-1:" && echo "    image: $IMG_SERVER1"
    [ -n "$IMG_SERVER2" ] && echo "  server-2:" && echo "    image: $IMG_SERVER2"
    [ -n "$IMG_PORTAL" ]  && echo "  portal-web:" && echo "    image: $IMG_PORTAL"
    [ -n "$IMG_ADMIN" ]   && echo "  admin-web:" && echo "    image: $IMG_ADMIN"
  } > "$OVERRIDE_FILE"
  printf '    %s$ cat %s%s\n' "$C_D" "$OVERRIDE_FILE" "$C_0"
  [ "$DRY_RUN" -eq 1 ] && sed 's/^/      /' "$OVERRIDE_FILE"
  return 0
}

# compose 一律在仓库根执行（.env 里的 OSS 凭证靠 project dir 加载）
cmp_up() { ( cd "$REPO_ROOT" && docker compose -f "$COMPOSE_BASE" -f "$OVERRIDE_FILE" up -d --no-deps --no-build "$@" ); }

# ★ dry-run 绝不能真的动容器：所有 compose up 都走这里
exe_cmp_up() {
  printf '    %s$ (cd %s && docker compose -f docker-compose.yml -f %s up -d --no-deps --no-build %s)%s\n' \
    "$C_D" "$REPO_ROOT" "${OVERRIDE_FILE##*/}" "$*" "$C_0"
  [ "$DRY_RUN" -eq 1 ] && return 0
  cmp_up "$@"
}

init_override() {
  OVERRIDE_DIR="$(mktemp -d "/tmp/qiqi-publish-${VER//\//_}.XXXXXX")"
  OVERRIDE_FILE="$OVERRIDE_DIR/compose.override.yml"
  info "override 文件：$OVERRIDE_FILE（临时，仓库不受影响）"
  IMG_SERVER1="$SERVER_REPO:$VER"; IMG_SERVER2="$SERVER_REPO:$VER"
  IMG_PORTAL="$PORTAL_REPO:$VER";  IMG_ADMIN="$ADMIN_REPO:$VER"
  write_override >/dev/null
  # 只读校验：base + override 能合并成合法配置（失败说明 override 写法/服务名有问题，
  # 这种情况绝对不能等到停容器时才发现）
  printf '    %s$ (cd %s && docker compose -f docker-compose.yml -f %s config --quiet)%s\n' \
    "$C_D" "$REPO_ROOT" "${OVERRIDE_FILE##*/}" "$C_0"
  if ! ( cd "$REPO_ROOT" && docker compose -f "$COMPOSE_BASE" -f "$OVERRIDE_FILE" config --quiet 2>&1 | sed 's/^/      /' ); then
    die "compose override 合并失败（配置本身不合法），未动任何容器"
  fi
  info "compose 配置合并校验通过 ✓"
}

# ------------------------------------------------------------------ 健康检查
probe_backend() { # $1=容器 $2=端口 → 打印 /api/health 的 body（失败则为空）
  docker exec "$1" wget -q -T 3 -O - "http://127.0.0.1:$2$HEALTH_PATH" 2>/dev/null
}

wait_backend_healthy() { # $1=容器 $2=端口
  local name="$1" port="$2"
  local deadline=$(( SECONDS + HEALTH_TIMEOUT ))
  local body rc
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" != "true" ]; then
      err "$name 容器已退出（不是慢，是挂了）"
      return 1
    fi
    body="$(probe_backend "$name" "$port")"; rc=$?
    if [ "$rc" -eq 0 ] && printf '%s' "$body" | grep -q '"status":"ok"'; then
      info "$name 健康 ✓（${HEALTH_PATH} → $body）"
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
  done
  err "$name 在 ${HEALTH_TIMEOUT}s 内没有变成健康（最后响应：${body:-<空>}）"
  info "最后 40 行容器日志："
  docker logs --tail 40 "$name" 2>&1 | sed 's/^/      /'
  return 1
}

wait_http_200() { # $1=url $2=名字
  local url="$1" label="$2" deadline=$(( SECONDS + HEALTH_TIMEOUT )) code
  while [ "$SECONDS" -lt "$deadline" ]; do
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$url" 2>/dev/null)"
    if [ "$code" = "200" ]; then info "$label 200 ✓（$url）"; return 0; fi
    sleep 2
  done
  err "$label 未在 ${HEALTH_TIMEOUT}s 内返回 200（最后 $code，$url）"
  return 1
}

# ------------------------------------------------------------------ 登录探测（发布门禁）
# 见脚本头【十】。目标只有一个：证明"这台新实例真的查得到库"。
# /api/health 只回 status/env/service，不碰 DB；登录必查 admin 表，是现成的真业务探针。
# ★ 但登录按**用户名**记失败次数（CaptchaStore，5 次之后要图形验证码 = 等于锁号），所以：
#   · 正例固定 admin，**只打 1 次**，绝不重试；
#   · 打之前先 GET /admin/auth/captcha-required（只读、不计数），已经在要验证码就不碰 admin；
#   · 失败只补 1 次负例 probe_<epoch>（库里没这个人，那条 401 恰好证明"查过库"），
#     负例只用来区分「口令错/账号被禁」和「服务没起来（5xx/连不上）」。

# 拼一版登录 JSON：口令只经环境变量进 python（不进 argv、不回显、不落盘）
login_json() { # $1=用户名
  ADMIN_USER="$1" ADMIN_PASS="$(cat "$ADMIN_SECRET_FILE")" python3 -c \
    'import json,os;print(json.dumps({"username":os.environ["ADMIN_USER"],"password":os.environ["ADMIN_PASS"]}))'
}

# 在容器内部打这台实例（绕开网关，直连 127.0.0.1:$port）。
# 请求体从 stdin 进来 → 容器内临时文件（umask 077，用完删）→ wget --post-file：
# 这样口令既不进宿主机 argv 也不进容器 argv（busybox wget 的 --post-data 会进 ps，坚决不用）。
# 打印 wget -S 的原始输出（响应头 + body），由调用方自己判状态码。
probe_login_post() { # $1=容器 $2=端口
  docker exec -i "$1" sh -c '
    umask 077
    f=/tmp/.qiqi-login-probe-$$.json
    cat > "$f" || exit 90
    wget -q -T 5 -S -O - --header="Content-Type: application/json" \
         --post-file="$f" "http://127.0.0.1:'"$2"'/admin/auth/login"
    rc=$?
    rm -f "$f"
    exit $rc
  ' 2>&1
}

# 这个用户名现在要不要图形验证码？只读接口，不增加失败计数 ——
# 用它先看一眼 admin 的计数状态，避免"为了探活反而把 admin 锁死"。
probe_captcha_required() { # $1=容器 $2=端口 → 打印 Result JSON（失败则为空）
  docker exec "$1" wget -q -T 5 -O - \
    "http://127.0.0.1:$2/admin/auth/captcha-required?username=admin" 2>/dev/null
}

# 从 wget -S 的输出里抓 HTTP 状态码（连不上 → 空）
http_code_of() {
  printf '%s' "$1" | grep -o 'HTTP/1\.[01] [0-9][0-9][0-9]' | tail -1 | awk '{print $2}'
}

# 负例：一次性用户名（probe_<epoch>）+ 随便一个口令。库里没有这个人 ⇒ 401「用户名或密码错误」，
# 而那条 401 恰好证明这台实例**查了库**并在正常处理请求（而不是 502/连不上）。
# 这个名字每次发布都不同，就算被计数也只会锁住一个不存在的用户，5 分钟后 Redis 的 TTL 自己清掉。
probe_login_negative() { # $1=容器 $2=端口
  local user="probe_$(date +%s)" out code
  out="$(printf '%s' "$(login_json "$user")" | probe_login_post "$1" "$2")"
  code="$(http_code_of "$out")"
  case "$code" in
    400|401)
      info "$1 负例探测 ✓（一次性用户 $user 不存在，服务仍正常查库并返回业务错误 HTTP $code）"
      return 0
      ;;
  esac
  err "$1 负例探测失败（用户 $user，HTTP ${code:-连不上}）→ 这台实例没有在正常服务"
  printf '%s' "$out" | tr -d '\r' | tail -c 300 | sed 's/^/      /' >&2
  return 1
}

# ★ 发布门禁本体：这台能登录（= 能查库）才允许被 reload 进 upstream
probe_login_instance() { # $1=容器 $2=端口 → 0=健康 1=不健康（调用方走 rollback_one_instance）
  local out code
  if [ "$PROBE_LOGIN" -eq 0 ]; then
    warn "PROBE_LOGIN=0：跳过登录门禁（只剩 /api/health 浅检查，DB 可用性无人验证）"
    return 0
  fi
  if [ ! -r "$ADMIN_SECRET_FILE" ]; then
    warn "读不到 $ADMIN_SECRET_FILE → 跳过登录门禁（浅健康检查照旧，但 DB 可用性没被验证）"
    return 0
  fi

  # 0) admin 是不是已经在要求图形验证码了？（只读、不计数）要了就绝不硬打，直接走负例
  if printf '%s' "$(probe_captcha_required "$1" "$2")" | grep -q '"data":true'; then
    warn "$1：admin 已在要求图形验证码（失败计数已满）→ 不碰 admin，改用一次性用户名做负例"
    probe_login_negative "$1" "$2"
    return $?
  fi

  # 1) 正例：admin，只打这一次
  out="$(printf '%s' "$(login_json admin)" | probe_login_post "$1" "$2")"
  code="$(http_code_of "$out")"
  if printf '%s' "$out" | grep -q '"accessToken"'; then
    info "$1 登录探测 ✓（admin 登录成功，accessToken 已隐去 ⇒ 这台查得到 admin 表）"
    return 0
  fi

  # 2) 判定只看 HTTP 状态码，不看响应体 —— busybox wget 遇到 4xx/5xx 会把 body 丢掉
  #    （只剩 "wget: server returned error: HTTP/1.1 401"），所以别指望 grep 业务提示语。
  #
  #    401/400 = 服务把请求处理到了业务层。结合"上面 0) 已经确认 admin 不在要验证码"，
  #              这条 401 只可能来自 `adminService.getOne(...)` 之后的口令比对 ⇒ **它查过库**。
  #    其余（5xx / 超时 / Connection refused / 没有状态行）= 根本没起来 ⇒ 不健康。
  case "$code" in
    400|401)
      # 口令文件过期或账号被禁也会是 401，但那跟"这台能不能查库"无关 ——
      # 用一次性用户名 probe_<epoch> 再判一次（绝不拿 admin 试第二次，5 次就锁号）。
      if printf '%s' "$(probe_captcha_required "$1" "$2")" | grep -q '"data":true'; then
        warn "$1：admin 现在已经在要求图形验证码（阈值被顶上去了，不一定是口令问题）→ 后面只用负例"
      fi
      warn "$1：admin 登录被拒（HTTP $code，$ADMIN_SECRET_FILE 可能过期或账号被禁）→ 用一次性用户判「这台查不查得到库」"
      if probe_login_negative "$1" "$2"; then
        warn "结论：$1 本身是好的（负例被正常查库并拒掉），问题在 admin 口令文件，请人工核对"
        return 0
      fi
      return 1
      ;;
  esac

  err "$1 登录探测失败（HTTP ${code:-连不上}）：这台没有在正常服务（不是慢，是没起来/连不上库）"
  printf '%s' "$out" | tr -d '\r' | tail -c 300 | sed 's/^/      /' >&2
  return 1
}

# ★ 重建实例后必须让网关重新解析 upstream 域名（nginx 只在加载配置时解析一次）
gateway_reload() {
  local i
  for i in 1 2 3; do
    printf '    %s$ docker exec %s nginx -t && nginx -s reload%s\n' "$C_D" "$C_GATEWAY" "$C_0"
    if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
    if docker exec "$C_GATEWAY" nginx -t 2>&1 | sed 's/^/      /' \
       && docker exec "$C_GATEWAY" nginx -s reload 2>&1 | sed 's/^/      /'; then
      info "网关已 reload（upstream 重新解析，零停机）"
      return 0
    fi
    warn "网关 reload 第 $i 次失败，2s 后重试"
    sleep 2
  done
  err "网关 reload 连续失败：新 IP 可能没生效（会 502）。手动：docker exec $C_GATEWAY nginx -s reload"
  return 1
}

# ------------------------------------------------------------------ 4. 构建后端镜像
build_backend() {
  step_begin "构建后端镜像"
  if [ "$SKIP_BACKEND" -eq 1 ]; then info "--skip-backend：跳过"; step_end skip; return 0; fi
  exe docker build -t "$SERVER_REPO:$VER" -f "$REPO_ROOT/$SERVER_REPO/Dockerfile" "$REPO_ROOT/$SERVER_REPO"
  local rc=$?
  if [ "$DRY_RUN" -eq 0 ] && [ "$rc" -ne 0 ]; then die "后端镜像构建失败（尚未动任何容器）"; fi
  if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else info "镜像 $SERVER_REPO:$VER 就绪 ✓"; step_end ok; fi
}

# ------------------------------------------------------------------ 5. 后端滚动重启
rollback_one_instance() { # $1=service $2=container $3=port $4=旧镜像ref
  local svc="$1" name="$2" port="$3" prev="$4"
  warn "回退 $name → $prev"
  case "$svc" in
    server-1) IMG_SERVER1="$prev" ;;
    server-2) IMG_SERVER2="$prev" ;;
  esac
  write_override >/dev/null
  exe_cmp_up "$svc" || { err "$name 回退启动命令失败"; return 1; }
  # 回退目标就是刚才还在服务的那台旧镜像，健康即认定恢复（不重复登录探测：
  # 登录失败是按用户名计数的，回退路径上再打一次只会白烧 admin 的失败配额）。
  wait_backend_healthy "$name" "$port" || { err "$name 回退后仍不健康，需要人工介入"; return 1; }
  info "$name 已用旧镜像恢复到健康状态 ✓"
  return 0
}

rolling_backend() {
  step_begin "后端滚动重启（不停机）"
  if [ "$SKIP_BACKEND" -eq 1 ]; then info "--skip-backend：跳过"; step_end skip; return 0; fi

  # 新镜像必须先存在，否则宁可不动手
  if [ "$DRY_RUN" -eq 0 ] && ! docker image inspect "$SERVER_REPO:$VER" >/dev/null 2>&1; then
    die "镜像 $SERVER_REPO:$VER 不存在，拒绝动容器"
  fi
  write_override >/dev/null

  local svc name port prev
  for spec in "server-1|$C_SERVER1|$P_SERVER1" "server-2|$C_SERVER2|$P_SERVER2"; do
    IFS='|' read -r svc name port <<< "$spec"
    # 回退目标永远是"滚动前的线上镜像"，不是本次的 $VER
    prev="$PREV_SERVER_IMG"

    log "  ── $name（另一台还在扛流量，这里可以放心停）"
    # 1) 优雅停机：DRAIN_SECONDS 让在途请求跑完；期间网关对该 upstream 的连接失败会自动重试到另一台
    exe docker stop --time "$DRAIN_SECONDS" "$name"
    if [ "$DRY_RUN" -eq 0 ] && docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -q true; then
      warn "$name 仍在运行，保留为兜底：跳过这一台的切换"
      continue
    fi
    # 2) 用新镜像重建（compose 负责沿用 env/网络/healthcheck，唯一变化是 image）
    exe_cmp_up "$svc" || die "$name 重建失败"
    # 3) 健康门禁：没通过就立刻退回旧镜像，并且**不去动另一台**
    if [ "$DRY_RUN" -eq 0 ]; then
      if ! wait_backend_healthy "$name" "$port"; then
        err "$name 新镜像不健康 → 立刻回退，且不碰另一台（保证至少有一台在服务）"
        rollback_one_instance "$svc" "$name" "$port" "$prev" || true
        step_end fail
        exit 1
      fi
      # 3.5) ★ 登录门禁（在健康之后、reload 之前）：/api/health 只证明 JVM 活着，
      #      这一步才证明"这台真的查得到库"。没过 = 这台不健康 = 立刻回旧镜像，
      #      绝不把它放进 upstream、绝不继续动另一台。见脚本头【十】。
      if ! probe_login_instance "$name" "$port"; then
        err "$name 登录探测不通过 → 判定不健康，立刻回退，且不碰另一台"
        rollback_one_instance "$svc" "$name" "$port" "$prev" || true
        step_end fail
        exit 1
      fi
    else
      info "[dry-run] 会轮询 $name:$port$HEALTH_PATH（上限 ${HEALTH_TIMEOUT}s）"
      info "[dry-run] 健康后追加登录探测（发布门禁，见脚本头【十】）："
      info "[dry-run]   GET  http://$name:$port/admin/auth/captcha-required?username=admin   # 只读，先看是否已被限流"
      info "[dry-run]   POST http://$name:$port/admin/auth/login  用户名=admin（口令读 $ADMIN_SECRET_FILE，不进 argv/不回显）"
      info "[dry-run]   → 200 + accessToken = 这台能查库；400/401 只补 1 次负例 probe_<epoch>（一次性用户名）"
      info "[dry-run]   → 5xx/超时/连不上 或 负例也失败 = 这台不健康 → rollback_one_instance 回旧镜像，且不碰另一台"
      info "[dry-run]   判定只看 HTTP 状态码（busybox wget 会丢掉 4xx 的响应体）"
      info "[dry-run]   验证码限流：同一用户名失败 5 次锁号，所以正例只打 1 次、失败不重试同一用户名"
    fi
    # 4) ★ 顺序红线：必须在动下一台之前 reload 网关。
    #    否则停下一台时，网关手里的这台还是旧 IP → 两个 upstream 同时不可用 = 断服。
    gateway_reload || die "网关联不上新实例，停止发布（另一台仍在服务，可以安全回退）"
  done

  if [ "$DRY_RUN" -eq 1 ]; then
    step_end dry
  else
    info "两台都已是 $VER、健康、且登录探测通过（能查库）✓"
    step_end ok
  fi
}

# ------------------------------------------------------------------ 6/7. 前端
build_frontend() {
  step_begin "构建前端镜像"
  if [ "$SKIP_FRONTEND" -eq 1 ]; then info "--skip-frontend：跳过"; step_end skip; return 0; fi
  local img dir pair
  # $img = 镜像名（用于 -t / docker image inspect）；$dir = 源码目录（用于 -f 与构建上下文）
  for pair in "$PORTAL_REPO=$PORTAL_DIR" "$ADMIN_REPO=$ADMIN_DIR"; do
    img="${pair%%=*}"; dir="${pair##*=}"
    # 前端 Dockerfile 是多阶段：node:22-alpine 里 npm ci + vite build --mode $FRONTEND_BUILD_MODE
    # → 不在宿主机跑 npm（省内存，宿主机也不落 node_modules）
    exe docker build -t "$img:$VER" --build-arg "BUILD_MODE=$FRONTEND_BUILD_MODE" \
        -f "$REPO_ROOT/$dir/Dockerfile" "$REPO_ROOT/$dir"
    if [ "$DRY_RUN" -eq 0 ] && ! docker image inspect "$img:$VER" >/dev/null 2>&1; then
      die "$img:$VER 构建失败（后端未受影响，可只回退前端）"
    fi
  done
  if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else info "两个前端镜像就绪 ✓"; step_end ok; fi
}

rolling_frontend() {
  step_begin "前端切换（两个一起换）"
  if [ "$SKIP_FRONTEND" -eq 1 ]; then info "--skip-frontend：跳过"; step_end skip; return 0; fi
  write_override >/dev/null
  # 两个前端是彼此独立的服务（5174/5176），一起换不会互相影响；
  # 单实例容器重建有 1~2s 空窗，但静态页重建本身就是秒级（见脚本头【四】缓存策略）。
  exe_cmp_up portal-web admin-web || die "前端重建失败（后端不受影响）"
  if [ "$DRY_RUN" -eq 0 ]; then
    wait_http_200 "http://127.0.0.1:5174/" "portal-web" || die "portal-web 起不来"
    wait_http_200 "http://127.0.0.1:5176/" "admin-web"  || die "admin-web 起不来"
  fi
  if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else info "前端切换完成 ✓"; step_end ok; fi
}

# ------------------------------------------------------------------ 8. 构建文档
build_docs() {
  step_begin "构建文档站"
  if [ "$SKIP_DOCS" -eq 1 ]; then info "--skip-docs：跳过"; step_end skip; return 0; fi
  drynote "node $DOCS_BUILD --mode=prod    # 产出 $DOCS_DIST/"
  if [ "$DRY_RUN" -eq 0 ]; then
    ( command -v node >/dev/null && node "$DOCS_BUILD" --mode=prod ) 2>&1 | sed 's/^/      /' \
      || die "文档构建失败（线上文档仍是上一版，未上传）"
    [ -f "$DOCS_DIST/index.html" ] || die "构建产物缺 index.html：$DOCS_DIST"
    local n; n=$(find "$DOCS_DIST" -type f | wc -l)
    info "dist-prod 就绪：$n 个文件 / $(du -sh "$DOCS_DIST" | cut -f1)"
    step_end ok
  else
    [ -d "$DOCS_DIST" ] && info "[dry-run] 不重建；现有 dist-prod：$(find "$DOCS_DIST" -type f | wc -l) 个文件"
    step_end dry
  fi
}

# --------------------------------------------------- 8.5 文档产物硬门禁（十一项体检）
# 见脚本头【十二】：口语稿 / PRIVATE_DOCS 那 12 篇私人笔记一旦上了 OSS + 刷了 CDN，
# svg/mp4/_vendor 下 js/css 是 30 天缓存，**公网上撤不干净**。所以必须在 publish_docs 之前拦：
# 这条门禁只看退出码，不看代码怎么写的（产物字节里有没有那些东西，由 check-docs-site.mjs 判）。
check_docs_gate() {
  step_begin "文档产物门禁（十一项体检：私人文稿零泄漏 + 产物洁净度）"
  if [ "$SKIP_DOCS" -eq 1 ]; then info "--skip-docs：跳过"; step_end skip; return 0; fi

  local checker="$REPO_ROOT/scripts/check-docs-site.mjs"
  local dist="$DOCS_DIST"
  # 仅演练用：--dry-run 时允许把门禁指向一份"手脚不干净"的副本，用来证明门禁真的会 die。
  # 真实发布（DRY_RUN=0）**不认**这个变量，永远只看 $DOCS_DIST —— 门禁不能被环境变量骗过去。
  if [ "$DRY_RUN" -eq 1 ] && [ -n "${DOCS_GATE_ROOT:-}" ]; then dist="$DOCS_GATE_ROOT"; fi

  [ -f "$checker" ] || die "门禁脚本缺失：$checker（没有门禁就不许上传：宁可这次不发文档，也不发一份没验过的产物）"
  command -v node >/dev/null 2>&1 || die "node 不可用，跑不了文档门禁（$checker）"
  if [ ! -d "$dist" ]; then
    if [ "$DRY_RUN" -eq 1 ]; then info "[dry-run] 没有产物目录 $dist，门禁无从跑起"; step_end dry; return 0; fi
    die "产物目录不存在：$dist（文档构建没成功？拒绝在没产物的状态下继续发布文档）"
  fi

  # 上传前只做静态检查：--skip-http（此刻公网还是上一版，探测公网既慢又没意义）
  drynote "node $checker --root $dist --skip-http    # 十一项全 PASS 才允许上传 OSS/刷 CDN"
  local rc=0
  node "$checker" --root "$dist" --skip-http 2>&1 | sed 's/^/      /' || rc=$?
  if [ "$rc" -ne 0 ]; then
    die "文档产物门禁未通过（EXIT=$rc）→ 拒绝上传：dist-prod 里有不该上公网的东西（口语稿 / PRIVATE_DOCS 私人笔记 / 私密文件名 / 原始录屏 / 内网 IP 与绝对路径 等，见上面每条命中）。\
一旦传上 OSS 再刷 CDN，svg/mp4/_vendor 是 30 天缓存，公网上撤不干净（见脚本头【十二】）"
  fi
  info "十一项体检全 PASS：没有口语稿/私人笔记，也没有私密文件名、原始录屏、绝对路径 ✓"
  if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else step_end ok; fi
}

# ------------------------------------------------------------------ 9. 发布文档（OSS + CDN）
# 说明：上传/删除/刷新全部在下面的 python 里做；--plan 模式（dry-run）只读本地文件、不联网。
publish_docs() {
  step_begin "发布文档（OSS+mmd+CDN）"
  if [ "$SKIP_DOCS" -eq 1 ]; then info "--skip-docs：跳过"; step_end skip; return 0; fi

  local plan_flag="--apply"
  [ "$DRY_RUN" -eq 1 ] && plan_flag="--plan"

  printf '    %s$ python3 <<oss2 同步>> %s dist=%s bucket=%s endpoint=%s prefix=""%s\n' \
    "$C_D" "$plan_flag" "$DOCS_DIST" "$DOCS_BUCKET" "$OSS_ENDPOINT" "$C_0"
  printf '    %s$ python3 <<oss2 备份>> %s %s/**/*.mmd → oss://%s/_src/%s\n' \
    "$C_D" "$plan_flag" "$DOCS_MMD_ROOT" "$DOCS_BUCKET" "$C_0"
  printf '    %s$ python3 <<CDN 刷新>>   %s %s 的 html/md/目录首页 + _vendor/ 下的 js/css + svg/mp4 等 30 天缓存对象%s\n' \
    "$C_D" "$plan_flag" "$DOCS_DOMAIN" "$C_0"

  if ! python3 - "$DOCS_DIST" "$DOCS_BUCKET" "$OSS_ENDPOINT" "$DOCS_MMD_ROOT" "$plan_flag" "$CRED_FILE" <<'PY'
# -*- coding: utf-8 -*-
"""docs 发布：dist-prod → oss://<bucket>/ ，docs/**/*.mmd → oss://<bucket>/_src/

规则（与 CDN 缓存策略配套，见 publish.sh 头部【六】）：
  html/htm/md  → no-cache        （发版必刷）
  svg/png/jpg/webp/gif/mp4/js/css/woff2 → 30 天  （URL 不变，不刷）
  _src/ 前缀只增不删：docs/ 不进 git，mmd 源码的唯一备份。
凭证只从 credentials.env 读，任何回显都过 redact()。
"""
import os
import sys

dist, bucket_name, endpoint, mmd_root, mode, cred_path = sys.argv[1:7]
APPLY = (mode == "--apply")

NO_CACHE = {"html", "htm", "md"}
LONG_CACHE = {"svg", "png", "jpg", "jpeg", "webp", "gif", "mp4", "webm",
              "js", "mjs", "css", "woff", "woff2", "ttf", "eot", "ico"}
CTYPE = {
    "html": "text/html; charset=utf-8",
    "htm": "text/html; charset=utf-8",
    "md": "text/markdown; charset=utf-8",
    "mmd": "text/plain; charset=utf-8",
    "svg": "image/svg+xml",
    "mp4": "video/mp4",
    "webm": "video/webm",
    "js": "application/javascript; charset=utf-8",
    "mjs": "application/javascript; charset=utf-8",
    "css": "text/css; charset=utf-8",
    "json": "application/json; charset=utf-8",
    "ico": "image/x-icon",
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "webp": "image/webp",
    "gif": "image/gif",
    "woff": "font/woff",
    "woff2": "font/woff2",
    "ttf": "font/ttf",
    "txt": "text/plain; charset=utf-8",
    "pdf": "application/pdf",
}


def load_creds(path):
    ak = sk = None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip().strip('"').strip("'")
            if k == "ALI_AK":
                ak = v
            elif k == "ALI_SK":
                sk = v
    if not ak or not sk:
        raise SystemExit("credentials.env 缺 ALI_AK / ALI_SK")
    return ak, sk


AK, SK = load_creds(cred_path)


def redact(text):
    out = str(text)
    for s in (AK, SK):
        if s:
            out = out.replace(s, "***REDACTED***")
    return out


def headers_for(key):
    ext = key.rsplit(".", 1)[-1].lower() if "." in key else ""
    ctype = CTYPE.get(ext, "application/octet-stream")
    if ext in NO_CACHE:
        cache = "no-cache"
    elif ext in LONG_CACHE:
        cache = "public, max-age=2592000"
    else:
        cache = "no-cache"
    return ctype, cache


def walk(root, prefix=""):
    out = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            abs_path = os.path.join(dirpath, fn)
            rel = os.path.relpath(abs_path, root).replace(os.sep, "/")
            out.append((prefix + rel, abs_path))
    out.sort()
    return out


site = walk(dist)
src_mmd = walk(mmd_root, "_src/")
src_mmd = [x for x in src_mmd if x[0].lower().endswith(".mmd")]

print("    站点对象 %d 个 / 源码 mmd %d 个" % (len(site), len(src_mmd)))
by_ext = {}
for key, _ in site:
    ext = key.rsplit(".", 1)[-1].lower() if "." in key else "(none)"
    by_ext[ext] = by_ext.get(ext, 0) + 1
print("    扩展名分布: " + ", ".join("%s=%d" % kv for kv in sorted(by_ext.items())))
for key, _ in site[:6]:
    print("      %-52s %s | %s" % (key, *headers_for(key)))
if len(site) > 6:
    print("      ...（共 %d 个）" % len(site))
if src_mmd:
    print("      源码备份示例: %s → oss://%s/%s" % (src_mmd[0][1], bucket_name, src_mmd[0][0]))

if not APPLY:
    print("    [plan] 未上传、未删除、未联网（dry-run）")
    sys.exit(0)

import oss2  # noqa: E402  （plan 模式不需要它）

bucket = oss2.Bucket(oss2.Auth(AK, SK), endpoint, bucket_name)

uploaded = 0
bytes_up = 0
for key, abs_path in site + src_mmd:
    ctype, cache = headers_for(key)
    bucket.put_object_from_file(
        key, abs_path,
        headers={"Content-Type": ctype, "Cache-Control": cache},
    )
    uploaded += 1
    bytes_up += os.path.getsize(abs_path)

# 删多余对象：只删站点根下的、且绝不碰 _src/（那是 mmd 源码唯一备份）
local_keys = {k for k, _ in site}
stale = []
for obj in oss2.ObjectIterator(bucket, prefix=""):
    key = obj.key
    if key.startswith("_src/"):
        continue
    if key not in local_keys:
        stale.append(key)

deleted = 0
for i in range(0, len(stale), 500):
    chunk = stale[i:i + 500]
    bucket.batch_delete_objects(chunk)
    deleted += len(chunk)

print("    ✓ 上传/覆盖 %d 个对象（%.1f MB），删除多余 %d 个，_src/ 保留"
      % (uploaded, bytes_up / 1048576.0, deleted))
PY
  then
    err "文档同步失败（OSS 侧可能是部分成功，重跑本步即可幂等收敛）"
    step_end fail
    if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
    die "文档发布失败（前端/后端已发布，可单独重跑：--skip-backend --skip-frontend）"
  fi

  # CDN：文件级刷新 html/md/目录首页 + _vendor/ + svg/mp4 等 30 天缓存对象
  # ★ 坑（2026-11-13 修正）：早期只刷 html/md/_vendor，理由是"图/视频吃 30 天缓存，
  #   刷了等于把 CDN 打空"。但 svg 架构图与 mp4 演示视频**文件名不带 content hash**，
  #   重新生成后 URL 不变 ⇒ 不刷就永远吃 30 天旧缓存 ⇒ 典型的"发布接口全绿、线上还是旧图"。
  #   现在把 LONG_TTL_EXT（svg/mp4/png/webp… 全部 30 天缓存对象）一并纳入默认清单。
  #   代价是这批对象发布后会集中回源一次，可接受；正确性优先。
  # ★ 每条 ObjectPath 必须 percent-encode + 提交后回读校验（原因见脚本头【十一】）
  if ! python3 - "$DOCS_DIST" "$DOCS_DOMAIN" "$CDN_REGION" "$plan_flag" "$CRED_FILE" <<'PY'
# -*- coding: utf-8 -*-
"""RefreshObjectCaches(ObjectType=File)：html/md/目录首页 + _vendor/ 下所有 js/css
+ svg/mp4 等 30 天缓存对象，批次 100。

★ svg/mp4 为什么必须刷（2026-11-13 修正）：它们的文件名没有 content hash，URL 恒定，
  又吃 max-age=2592000 ⇒ 重新生成/重录后不刷就一直是旧版。早期"图/视频一律不刷"的
  取舍会把这种情况变成"发布成功但线上还是旧图"，因此改为纳入默认清单。

为什么仍只用文件级刷新：只刷本次实际发布的那批 URL；目录刷新/全站刷新会把 30 天缓存
规则一起打掉（RUNBOOK 六节），代价远大于逐条 File 刷新。
为什么 _vendor/ 必须刷：docsify 的 _vendor/*.js、*.css 文件名**没有 content hash**
（永远叫 docsify.min.js / theme-simple.css），URL 不变 ⇒ 吃 30 天缓存 ⇒ 改了样式/换了插件
不刷就永远看不到新版。所以每次发布都把它们全部刷一遍（运行时扫目录，不硬编码文件名，
将来加插件自动跟上）。

★★ 为什么每条 ObjectPath 都必须 percent-encode（2026-11 实测，别"简化"回去）★★
  阿里云 CDN 的 RefreshObjectCaches 对中文**不做容错**，而且是**静默失败**：
    - 原样提交中文路径
        https://docs.rose7.cn/_assets/img/flows/01-架构/1-系统架构图.svg
      → 接口返回 200 + RefreshTaskId；DescribeRefreshTasks 里 Status=Complete /
        Process=100%，**清单里明确列出了该对象**（看起来完全成功）
      → 但线上仍是 X-Cache: HIT / Age 一路涨到 307（SaveTime 一动不动），返回的还是旧内容
    - 同一条路径 quote(..., safe="/") 编码后提交
        https://docs.rose7.cn/_assets/img/flows/01-%E6%9E%B6%E6%9E%84/1-...svg
      → 17 秒内 X-Cache: MISS、Age 归零、SaveTime 刷新，拿到新内容
  机制：CDN 的缓存 key 是**编码后的 URL**（浏览器/curl 发的也是编码串）。原样中文的
  ObjectPath 跟这个 key 对不上 ⇒ 任务"成功"完成，但一份缓存都没被失效。文档站有 98 条
  中文 md/svg 路径，全都在这个坑里 ⇒ "每次发版都报告成功、线上却没变"。
  所以：绝不把 encode_object_path() 去掉；提交完还有两道回读（canary 探针 + 5 条抽检，
  见文件末尾）——这个接口错了也会返回成功，没有回读就等于没有证据。
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from urllib.parse import quote, unquote

dist, domain, region, mode, cred_path = sys.argv[1:6]
APPLY = (mode == "--apply")
VERIFY_TIMEOUT = int(os.environ.get("CDN_VERIFY_TIMEOUT") or 180)  # 回读校验轮询上限（秒）
VERIFY_SAMPLE = int(os.environ.get("CDN_VERIFY_SAMPLE") or 5)      # 抽检条数
CANARY_ON = (os.environ.get("CDN_CANARY") or "1") != "0"           # 编码自检探针开关

NO_CACHE_EXT = {"html", "htm", "md"}                               # TTL 0，每次回源
LONG_TTL_EXT = {"svg", "png", "jpg", "jpeg", "webp", "gif", "mp4", "webm",
                "js", "css", "woff", "woff2", "ttf", "ico"}        # 30 天，会停在 HIT

ak = sk = None
for line in open(cred_path, encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    k, v = k.strip(), v.strip().strip('"').strip("'")
    if k == "ALI_AK":
        ak = v
    elif k == "ALI_SK":
        sk = v


def redact(text):
    out = str(text)
    for s in (ak, sk):
        if s:
            out = out.replace(s, "***REDACTED***")
    return out


def fail(msg):
    print("      ✗ " + redact(msg), file=sys.stderr)
    sys.exit(3)


# ---------------------------------------------------------------- 路径 → 编码
def encode_object_path(path):
    """OSS 对象路径 → 可直接提交给 CDN / 可直接请求的 URL path（唯一入口，别绕开）。

    三条规矩（改之前先看本文件头部的实测现象）：
      1) 根路径（"" / "/"）原样返回 "/"，不动；
      2) safe="/" 必须给 —— 否则斜杠会被编成 %2F，路径层级消失，刷新照样打空；
      3) 已经是编码串的输入**不要**二次编码 —— 先 unquote 还原成原始串再 quote，
         于是 "%E6%9C%BA" 与 "机" 两条输入得到同一个输出，%E6 不会变成 %25E6。
    """
    if not path or path == "/":
        return "/"
    return "/" + quote(unquote(path).lstrip("/"), safe="/")


def plain_path(rel):
    """站点相对路径 → 未编码的 URL path（只用于打印对比，不许拿去请求/提交）。"""
    return "/" + rel.lstrip("/") if rel else "/"


def url_of(rel):
    """站点相对路径（"" = 根）→ 提交给 CDN 的完整 URL；path 部分已 percent-encode。"""
    return "https://%s%s" % (domain, encode_object_path(rel))


def ext_of(rel):
    return rel.rsplit(".", 1)[-1].lower() if "." in rel else ""


# ① html / md / 每个目录首页（docsify 是运行时 fetch .md 的 SPA，md 必须刷）
site_rels = [""]                     # "" 就是站点根 https://<domain>/
long_ttl_rels = []                   # 30 天缓存的对象：唯一能稳定观察到 X-Cache: HIT 的一类
for dirpath, _d, filenames in os.walk(dist):
    for fn in filenames:
        abs_path = os.path.join(dirpath, fn)
        rel = os.path.relpath(abs_path, dist).replace(os.sep, "/")
        low = fn.lower()
        if low.endswith((".html", ".htm", ".md")):
            site_rels.append(rel)
            if low == "index.html":
                parent = rel.rsplit("/", 1)[0] if "/" in rel else ""
                site_rels.append((parent + "/") if parent else "")
        if ext_of(rel) in LONG_TTL_EXT:
            long_ttl_rels.append(rel)
site_rels = sorted(set(site_rels))
long_ttl_rels = sorted(set(long_ttl_rels))

# ② _vendor/ 下的所有 js / css（文件名不带 hash，改了不刷就一直吃旧缓存）
#    运行时扫 dist/_vendor/，不硬编码文件名：换主题、加插件都自动跟上。
vendor_rels = []
vendor_dir = os.path.join(dist, "_vendor")
for dirpath, _d, filenames in os.walk(vendor_dir):
    for fn in filenames:
        if fn.lower().endswith((".js", ".css")):
            rel = os.path.relpath(os.path.join(dirpath, fn), dist).replace(os.sep, "/")
            vendor_rels.append(rel)
vendor_rels = sorted(set(vendor_rels))

rels = sorted(set(site_rels) | set(vendor_rels) | set(long_ttl_rels))
urls = [url_of(r) for r in rels]             # 交给接口的就是这一份（path 已编码）
need_enc = [r for r in rels if encode_object_path(r) != plain_path(r)]

print("    CDN 刷新清单 %d 条（html/md/目录首页 %d 条 + _vendor js/css %d 条 + "
      "svg/mp4 等 30 天缓存对象 %d 条；其中含非 ASCII、必须编码的 %d 条）"
      % (len(rels), len(site_rels), len(vendor_rels), len(long_ttl_rels), len(need_enc)))
for r in rels[:5]:
    print("      %-58s%s" % (plain_path(r), encode_object_path(r) if
                              encode_object_path(r) != plain_path(r) else ""))
if len(rels) > 5:
    print("      ...（共 %d 条）" % len(rels))
for r in need_enc[:3]:                       # 单把"要编码的那几条"抽出来看，别被 ASCII 挡住
    print("      必编码示例  %s\n                 → %s" % (plain_path(r), encode_object_path(r)))
if vendor_rels:
    print("      _vendor：%s" % ", ".join(os.path.basename(r) for r in vendor_rels))
else:
    print("      _vendor：无 js/css（%s 不存在或为空，跳过）" % vendor_dir)
print("      编码自检探针（canary）：%s" % ("开" if CANARY_ON else "关（CDN_CANARY=0）"))

if not APPLY:
    print("    [plan] 未调用 CDN 接口（dry-run）")
    sys.exit(0)

from aliyunsdkcore.client import AcsClient
# ★ 是 RefreshObjectCaches（刷新），不是 PushObjectCache（预热）：前者才让已有缓存立即失效
from aliyunsdkcdn.request.v20180510.RefreshObjectCachesRequest import RefreshObjectCachesRequest

client = AcsClient(ak, sk, region)


def head_once(url, timeout=10):
    """HEAD 一次，返回 (status, x_cache, age, save_time)。

    只用 HEAD（不下正文），且**不做任何 cache-busting**：要看的就是边缘那份副本
    现在到底是 HIT（说明没被失效）还是 MISS/EXPIRED（说明回源刷过了）。
    """
    req = urllib.request.Request(url, method="HEAD",
                                 headers={"User-Agent": "qiqi-publish-verify/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            h = {k.lower(): v for k, v in resp.headers.items()}
            age = h.get("age", "")
            return (resp.status, h.get("x-cache", ""),
                    int(age) if age.isdigit() else None, h.get("x-swift-savetime", ""))
    except Exception as exc:              # 校验本身失败不该炸栈，记下来给人看
        return 0, "ERR:%s" % str(exc)[:80], None, ""


def submit(object_paths):
    """提交一批 ObjectPath → (http_status, task_id, request_id, body)。

    用 _implementation_of_do_action 拿**真实 HTTP 状态码**：公开的 do_action_with_exception
    只回 body，非 2xx 直接抛异常，日志里看不到"到底几号码"。没有这个方法就退回公开 API。
    """
    req = RefreshObjectCachesRequest()
    req.set_ObjectPath("\n".join(object_paths))   # ← 编码后的 URL；原样中文会被静默丢弃
    req.set_ObjectType("File")
    req.set_accept_format("JSON")                  # 要 JSON 返回体，好解析 RefreshTaskId
    if hasattr(client, "_implementation_of_do_action"):
        status, _hdrs, body, exc = client._implementation_of_do_action(req)
        if exc:
            fail("提交异常（拿不到 HTTP 状态码）：%s" % exc)
        body = body.decode("utf-8", "replace")
    else:
        status, body = 200, client.do_action_with_exception(req).decode("utf-8", "replace")
    try:
        info = json.loads(body)
    except ValueError:
        # 兜底：万一网关/SDK 回了 XML，也要能抠出 TaskId（别把成功误判成失败）
        info = {}
        m = re.search(r"<RefreshTaskId>(\d+)</RefreshTaskId>", body)
        if m:
            info["RefreshTaskId"] = m.group(1)
        m = re.search(r"<RequestId>([^<]+)</RequestId>", body)
        if m:
            info["RequestId"] = m.group(1)
    return status, info.get("RefreshTaskId"), info.get("RequestId"), body


def wait_flushed(rel, timeout):
    """轮询一条 URL，直到它离开 HIT（或 Age 回落）。返回 (bool, 最后一行观测描述, 轮数)。"""
    base_age = head_once(url_of(rel))[2]
    started = time.time()
    last = ""
    while time.time() - started < timeout:
        time.sleep(10)
        st, xc, age, _sv = head_once(url_of(rel))
        ok = bool(xc) and not xc.startswith("HIT")
        if not ok and age is not None and base_age is not None and age + 5 < base_age:
            ok = True
        last = ("[t+%3ds] %-58s HTTP %-3s X-Cache: %-22s Age: %s  %s"
                % (int(time.time() - started), encode_object_path(rel), st or "-", xc or "-",
                   age if age is not None else "-", "✓ 已失效" if ok else "… 等待"))
        print("      " + last)
        if ok:
            return True, last, int(time.time() - started)
    return False, last, int(time.time() - started)


# ---------------- ① 主清单：逐批打印 HTTP 状态码 + RefreshTaskId，任何一批不对就 fail ----------
total = 0
task_ids = []
for i in range(0, len(urls), 100):
    batch = urls[i:i + 100]
    status, tid, rid, body = submit(batch)
    print("      批次 %d: HTTP %s | %d 条 | RefreshTaskId=%s | RequestId=%s"
          % (i // 100 + 1, status, len(batch), tid, rid))
    if status != 200:
        fail("批次 %d 返回 HTTP %s（非 200）→ 刷新失败：%s" % (i // 100 + 1, status, body[:200]))
    if not tid:
        fail("批次 %d 没拿到 RefreshTaskId（HTTP %s，返回体 %s）→ 视为刷新失败"
             % (i // 100 + 1, status, body[:200]))
    task_ids.append(str(tid))
    total += len(batch)
print("    ✓ CDN 刷新已提交 %d 条 / %d 个批次（RefreshTaskId: %s）"
      % (total, len(task_ids), ", ".join(task_ids)))

# ---------------- ② 编码自检探针（canary）：唯一能"物理证明"刷新生效的对象类 ----------
# 主清单刷的是 html/md/_vendor：html 与 md 在 CDN 上是 TTL 0（no-cache，每次请求都回源），
# 它们的 X-Cache **永远**是 MISS ⇒ 光看它们根本区分不出"刷新生效"和"刷新被静默丢弃"。
# 而 svg/png 这类 30 天缓存的对象会稳定停在 X-Cache: HIT，只有它们能被观察到"从 HIT 掉下去"。
# 所以每次发布额外刷**一条中文图**（1 条，代价可忽略），看它是否掉 HIT：
#   掉下去      → percent-encode 确实被 CDN 认了 ✔
#   掉不下去    → 正是"原样中文被静默丢弃"的指纹 → fail，拦住发布
# 想关掉：CDN_CANARY=0（不建议；关掉就等于退回"只看接口说成功"）。
canary_rels = [r for r in long_ttl_rels if encode_object_path(r) != plain_path(r)]
if not CANARY_ON:
    print("    ⚠ 已用 CDN_CANARY=0 关闭编码自检探针（只保证接口受理，不保证真的生效）")
elif not canary_rels:
    print("    ⚠ dist 里没有中文命名的 30 天缓存对象（svg/png…），跳过编码自检探针")
else:
    canary = canary_rels[0]
    st0, xc0, age0, _sv0 = head_once(url_of(canary))
    print("    编码自检探针 %s" % encode_object_path(canary))
    print("      刷新前: HTTP %s | X-Cache: %s | Age: %s" % (st0 or "-", xc0 or "-",
                                                          age0 if age0 is not None else "-"))
    if not (xc0 or "").startswith("HIT"):
        print("      ⚠ 基线不是 HIT（该边缘还没有这份缓存），本次无法判定 → 跳过（不 fail，"
              "下一条探针会自动顶上）")
    else:
        cst, ctid, crid, cbody = submit([url_of(canary)])
        print("      提交: HTTP %s | RefreshTaskId=%s | RequestId=%s" % (cst, ctid, crid))
        if cst != 200 or not ctid:
            fail("探针提交失败（HTTP %s）：%s" % (cst, cbody[:200]))
        cok, clast, ctook = wait_flushed(canary, VERIFY_TIMEOUT)
        if cok:
            print("    ✓ 编码自检探针通过：%d 秒内 X-Cache 离开 HIT（percent-encode 生效）" % ctook)
        else:
            fail("编码自检探针失败：%d 秒内 %s 仍是 HIT（最后一次观测：%s）—— 刷新没真正生效，"
                 "首要怀疑 ObjectPath 没做 percent-encode（见本文件头部实测说明）"
                 % (VERIFY_TIMEOUT, encode_object_path(canary), clast))

# ---------------- ③ 主清单抽检 N 条：基线是 HIT 的才有可比性，其余只记录不改判 ----------
samples = need_enc[:VERIFY_SAMPLE]
if not samples:
    print("    ⚠ 主清单里没有需要编码的路径（无中文），跳过抽检")
else:
    print("    主清单抽检 %d 条（HEAD 一次，记录 X-Cache/Age 供人工复核）" % len(samples))
    hit_samples = []
    for r in samples:
        st, xc, age, _sv = head_once(url_of(r))
        if (xc or "").startswith("HIT"):
            hit_samples.append(r)
        print("      %-58s HTTP %-3s X-Cache: %-22s Age: %s"
              % (encode_object_path(r), st or "-", xc or "-", age if age is not None else "-"))
    if not hit_samples:
        print("    ⚠ 抽检对象基线均非 HIT（html/md 是 TTL 0，每次请求都回源）⇒ X-Cache 前后"
              "无可比性；本次生效性以 canary 探针 + 每批次 HTTP 200/RefreshTaskId 为准")
    else:
        pending = set(hit_samples)
        started = time.time()
        while pending and time.time() - started < VERIFY_TIMEOUT:
            time.sleep(10)
            for r in sorted(pending):
                st, xc, age, _sv = head_once(url_of(r))
                ok = bool(xc) and not xc.startswith("HIT")
                print("      [t+%3ds] %-58s HTTP %-3s X-Cache: %-22s Age: %s  %s"
                      % (int(time.time() - started), encode_object_path(r), st or "-",
                         xc or "-", age if age is not None else "-", "✓" if ok else "…"))
                if ok:
                    pending.discard(r)
        if pending and len(pending) == len(hit_samples):
            fail("抽检 %d 条**全部**仍是 HIT → 刷新没有真正生效：%s"
                 % (len(pending), ", ".join(sorted(encode_object_path(r) for r in pending))[:300]))
        if pending:
            print("    ⚠ 仍有 %d 条抽检对象在 %d 秒内没看到 X-Cache 变化；CDN 传播有快有慢，"
                  "稍后 curl -I 复核即可（不阻塞发布）" % (len(pending), VERIFY_TIMEOUT))
        else:
            print("    ✓ 抽检 %d 条全部在 %d 秒内离开 HIT" % (len(hit_samples), int(time.time() - started)))
PY
  then
    err "CDN 刷新失败（文档已上传，只是缓存可能还没失效；可手动刷或稍后重跑）"
    step_end fail
    die "CDN 刷新失败"
  fi

  if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else info "文档已发布 ✓"; step_end ok; fi
}

# ------------------------------------------------------------------ 10. 冒烟
smoke_one() { # $1=label $2=url $3=期望码
  local label="$1" url="$2" want="$3" code
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 12 "$url" 2>/dev/null)"
  if [ "$code" = "$want" ]; then
    info "$label  $code ✓  $url"
    return 0
  fi
  err "$label  期望 $want 实得 ${code:-000}  $url"
  return 1
}

smoke_login() {
  local url="http://127.0.0.1:18080/admin/auth/login" pw resp try
  if [ ! -r "$ADMIN_SECRET_FILE" ]; then
    warn "无 $ADMIN_SECRET_FILE，跳过登录冒烟"; return 0
  fi
  pw="$(cat "$ADMIN_SECRET_FILE")"
  # 口令走环境变量进 python，不出现在 argv / 日志里
  local body
  body="$(ADMIN_PASS="$pw" python3 -c 'import json,os;print(json.dumps({"username":"admin","password":os.environ["ADMIN_PASS"]}))')"
  for try in 1 2 3; do   # 网关轮询两台；单台刚起时可能命中，重试几次
    resp="$(curl -s -m 10 -X POST "$url" -H 'Content-Type: application/json' --data "$body" 2>/dev/null)"
    if printf '%s' "$resp" | grep -q '"accessToken"'; then
      info "登录接口可用 ✓（拿到 accessToken，已隐去）"
      return 0
    fi
    if printf '%s' "$resp" | grep -q '验证码'; then
      warn "登录接口要求图形验证码（风控已开启），脚本不做图形码 → 本项跳过（接口本身是通的）"
      return 0
    fi
    sleep 2
  done
  err "登录接口失败：$(printf '%s' "$resp" | head -c 300)"
  return 1
}

smoke_picture() {
  # 抽一张真实商品图：优先查库拿 pic（/qiqi-mall/** 相对路径），退回 OSS 列表
  local pic code url
  local pw
  pw="$(grep -E '^MYSQL_ROOT_PASSWORD=' "$REPO_ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2- || true)"
  if [ -n "$pw" ] && docker ps --format '{{.Names}}' | grep -qx "$C_MYSQL"; then
    pic="$(docker exec -e MYSQL_PWD="$pw" "$C_MYSQL" mysql -N -B -uroot \
            -e "select pic from product where pic like '/qiqi-mall/%' limit 1" qiqi_mall_test 2>/dev/null || true)"
  fi
  if [ -z "${pic:-}" ]; then
    pic="$(python3 - "$CRED_FILE" <<'PY' 2>/dev/null || true
import sys
ak = sk = None
for line in open(sys.argv[1], encoding="utf-8"):
    line = line.strip()
    if "=" in line and not line.startswith("#"):
        k, v = line.split("=", 1)
        if k.strip() == "ALI_AK": ak = v.strip()
        if k.strip() == "ALI_SK": sk = v.strip()
import oss2
b = oss2.Bucket(oss2.Auth(ak, sk), "oss-cn-beijing.aliyuncs.com", "qiqi-mall-static")
for obj in oss2.ObjectIterator(b, prefix="qiqi-mall/", max_keys=10):
    print("/" + obj.key); break
PY
)"
  fi
  if [ -z "${pic:-}" ]; then
    warn "没找到商品图样本，跳过 CDN 图片冒烟"; return 0
  fi
  url="https://cdn.rose7.cn${pic}"
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 12 "$url")"
  if [ "$code" = "200" ]; then
    info "CDN 商品图 200 ✓  $url"
    return 0
  fi
  err "CDN 商品图期望 200 实得 $code  $url"
  return 1
}

smoke_gateway_both() {
  # 网关是轮询：单次 200 只证明"至少一台活着"，连打 8 次全 200 才说明两台都在
  local i code bad=0
  for i in $(seq 1 8); do
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "http://127.0.0.1:18080/api/health")"
    [ "$code" = "200" ] || { bad=$((bad+1)); }
  done
  if [ "$bad" -eq 0 ]; then
    info "网关 8/8 次健康（两台都在轮询里）✓"
    return 0
  fi
  err "网关 8 次里有 $bad 次不是 200 → 很可能有一台没进 upstream（检查 nginx reload）"
  return 1
}

smoke() {
  step_begin "冒烟"
  if [ "$DRY_RUN" -eq 1 ]; then
    info "[dry-run] 将检查（不真的发请求）："
    printf '      %s\n' \
      "https://mall.rose7.cn                  → $EXPECT_MALL" \
      "https://admin.rose7.cn                 → $EXPECT_ADMIN（Caddy basic auth）" \
      "https://docs.rose7.cn                  → $EXPECT_DOCS" \
      "https://cdn.rose7.cn/qiqi-mall/**      → 200（抽一张真实商品图）" \
      "http://127.0.0.1:18080/api/health      → $EXPECT_HEALTH"
    info "[dry-run] 登录接口：POST http://127.0.0.1:18080/admin/auth/login（口令读 $ADMIN_SECRET_FILE，不回显）"
    info "[dry-run] 网关双实例：连续 8 次 /api/health 全 200"
    step_end dry
    return 0
  fi

  local fails=0
  smoke_one "mall.rose7.cn " "https://mall.rose7.cn"      "$EXPECT_MALL"  || fails=$((fails+1))
  smoke_one "admin.rose7.cn" "https://admin.rose7.cn"     "$EXPECT_ADMIN" || fails=$((fails+1))
  smoke_one "docs.rose7.cn " "https://docs.rose7.cn"      "$EXPECT_DOCS"  || fails=$((fails+1))
  smoke_one "gateway local"  "http://127.0.0.1:18080/api/health" "$EXPECT_HEALTH" || fails=$((fails+1))
  smoke_gateway_both || fails=$((fails+1))
  smoke_picture      || fails=$((fails+1))
  smoke_login        || fails=$((fails+1))

  if [ "$fails" -ne 0 ]; then
    step_end fail
    warn "冒烟有 $fails 项不通过 —— 不自动回滚（先看一眼日志，判断是新版本的问题还是链路问题）"
    warn "真回滚：scripts/publish.sh --rollback ${PREV_SERVER_IMG#*:}"
    return 1
  fi
  info "冒烟全绿 ✓"
  step_end ok
  return 0
}

# ------------------------------------------------------------------ 11. 收尾
final_report() {
  step_begin "收尾"
  local total=$(( SECONDS - T0 ))
  info "版本号     : $VER"
  info "后端镜像   : $SERVER_REPO:$VER（server-1 + server-2）"
  info "前端镜像   : $PORTAL_REPO:$VER / $ADMIN_REPO:$VER"
  info "tag commit : ${TAG_COMMIT:-（回滚任务：未新建 tag）}"
  info "耗时       : ${total}s"
  if [ "$DRY_RUN" -eq 1 ]; then
    info "模式       : DRY-RUN（未打 tag、未构建、未重启、未上传、未刷 CDN）"
  fi
  # ★ 用了逃生开关就在报告里标红留痕（不是告警完就算，事后复盘要能一眼看到"这次发布冒了 OOM 的险"）
  if [ "$FORCE_LOW_MEM" -eq 1 ]; then
    local memstate="门禁本来就能过（开关没用上）"
    [ "$FORCE_LOW_MEM_USED" -eq 1 ] && memstate="内存/交换区门禁被强制跳过，OOM 风险自负"
    printf '%s%s ⚠ --force-low-mem 已启用：%s；本次不作内存安全承诺%s\n' \
      "$C_B" "$C_R" "$memstate" "$C_0"
  fi
  # ★ 回滚的边界必须写在**运行输出**里（不只是脚本注释）：这两件事回不去，人得当场知道
  if [ "$MODE" = "rollback" ]; then
    printf '%s  回滚的边界（必读）：%s\n' "$C_Y" "$C_0"
    printf '%s    · 文档站不回滚：dist-prod 是整目录覆盖、没有历史版本；要退只能 checkout 对应版本的 docs 快照重新 build（docs/ 不进 git）%s\n' "$C_Y" "$C_0"
    printf '%s    · 数据库不可回滚：已执行的 migration、已写入的数据都回不去（见脚本头【五】）%s\n' "$C_Y" "$C_0"
    printf '%s    · 能回滚的只有镜像：容器重建即可，秒级%s\n' "$C_Y" "$C_0"
  fi
  local tags
  tags="$(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -E "^(qiqi-mall-server|qiqi-portal-web|qiqi-admin-web):v" | sort -u | tr '\n' ' ')"
  [ -n "$tags" ] && info "现存版本镜像: $tags"
  info "镜像保留最近 5 个 tag，旧的清掉："
  info "  docker rmi <上面列出的旧 tag>        # 或 docker image prune -a（谨慎，会删所有未用镜像）"
  if [ "$MODE" = "publish" ]; then
    info "回退到任一旧版本（后端滚动、不停机）："
    info "  scripts/publish.sh --rollback vX.Y.Z"
  fi
  step_end ok
}

# ------------------------------------------------------------------ 回滚入口
run_rollback() {
  log "${C_B}回滚到 $VER${C_0}（dry-run=$DRY_RUN）"
  IMG_SERVER1="$SERVER_REPO:$VER"; IMG_SERVER2="$SERVER_REPO:$VER"
  IMG_PORTAL="$PORTAL_REPO:$VER";  IMG_ADMIN="$ADMIN_REPO:$VER"
  write_override >/dev/null

  local svc name port
  # 与发布同样的滚动节奏：一次只停一台，健康通过 + 网关 reload 之后才动下一台
  for spec in "server-1|$C_SERVER1|$P_SERVER1" "server-2|$C_SERVER2|$P_SERVER2"; do
    IFS='|' read -r svc name port <<< "$spec"
    log "  ── $name（另一台还在扛流量）"
    exe_quiet docker stop --time "$DRAIN_SECONDS" "$name"
    exe_cmp_up "$svc" || die "$name 回滚启动失败"
    if [ "$DRY_RUN" -eq 0 ]; then
      wait_backend_healthy "$name" "$port" || die "$name 回滚后不健康，人工介入"
    else
      info "[dry-run] 会轮询 $name:$port$HEALTH_PATH（上限 ${HEALTH_TIMEOUT}s）"
    fi
    gateway_reload || die "网关 reload 失败"
  done
  exe_cmp_up portal-web admin-web || die "前端回滚启动失败"
  if [ "$DRY_RUN" -eq 0 ]; then
    wait_http_200 "http://127.0.0.1:5174/" "portal-web" || die "portal-web 回滚后起不来"
    wait_http_200 "http://127.0.0.1:5176/" "admin-web"  || die "admin-web 回滚后起不来"
  fi
  # 收尾再强调一次（final_report 里还会按 MODE=rollback 打印完整边界）：
  # 能回滚的只有镜像；文档站不回滚、数据库不可回滚。
  log "${C_Y}注意：只回滚了镜像 —— 文档站不会跟着回滚（dist-prod 无版本历史），数据库不可回滚（已执行的 migration / 已写入的数据回不去）。${C_0}"
}

# ------------------------------------------------------------------ main
main() {
  parse_args "$@"
  T0=$SECONDS
  log "${C_B}qiqi-mall publish${C_0}  mode=$MODE version=$VER dry-run=$DRY_RUN skip(docs=$SKIP_DOCS frontend=$SKIP_FRONTEND backend=$SKIP_BACKEND)"

  preflight          # 自己管失败退出（真实发布失败即 die；dry-run 下只警告并继续）
  init_override

  if [ "$MODE" = "rollback" ]; then
    step_begin "回滚"
    run_rollback
    if [ "$DRY_RUN" -eq 1 ]; then step_end dry; else step_end ok; fi
    final_report
    return 0
  fi

  do_tag
  do_push_tag
  build_backend
  rolling_backend
  build_frontend
  rolling_frontend
  build_docs
  check_docs_gate     # ★硬门禁：产物里有口语稿/私人文稿就 die，一个字节都不上传
  publish_docs

  if ! smoke; then
    final_report
    exit 2   # 冒烟失败：已发布但需人看，退出码 2 区别于"半路中断"的 1
  fi
  final_report
}

main "$@"
