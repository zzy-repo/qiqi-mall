文档站构建配置（版本管理用的快照）
====================================

为什么有这个目录
  文档站的实际构建发生在仓库之外（/root/docsify 与 /root/docs-rspress），
  那些目录不在 git 里。曾经因此吃过一次亏：Rspress 的 cleanUrls 修复改在
  /root/docs-rspress/rspress.config.ts 上，没有任何版本记录，下次重建就可能丢。

  所以把「决定产物形态的那几个文件」定期同步到这里存档。
  **这里不是运行位置，是备份/审阅用途**；真要重建站点，仍按下面「实际位置」操作。

实际位置与职责
  /root/docsify/                docsify 站（旧方案，现 :3028 保留作回退档）
    build-material.mjs          内容生成器：读 qiqi-mall/docs/**（mmd + 口语稿），
                                产出 md + _sidebar.md。**分组与顺序的唯一真相源**，
                                Rspress 也复用它（--target=rspress），所以两边不会漂移
    _deploy/nginx.conf          静态服务配置（/_assets/{img,video} 走 alias 到宿主目录）
  /root/docs-rspress/           Rspress 站（现 :3027 dev / :3010 prod / docs.rose7.cn 公网）
    package.json                依赖锁定（@rspress/core 2.1.3）
    rspress.config.ts           站点配置，含三处关键自定义：
                                  · route.cleanUrls: false  ← 2.1.3 默认 true 会导致
                                    链接不带 .html，纯 OSS 静态托管下 F5/分享即 404
                                  · globalStyles → theme/custom.css（4 列表格窄屏不撑破）
                                  · head → public/legacy-redirect.js（旧 docsify #/ 链接兜底）
    _deploy/nginx.conf          dev 站静态服务（user root 是因为产物权限 600，
                                只读挂载 + 仅内网 + 容器内，可接受）
    doc/                        内容（index.md + material/**，由 docsify 的生成器写入）
  /root/.qiqi-standing/docs-dev-refresh.sh
                                一条命令重建 Rspress dev 并跑 12 项体检 + 抽查 :3027

同步命令（改完上面任何一处，请更新本目录）
  cp /root/docs-rspress/{package.json,rspress.config.ts} /root/qiqi-mall/docs-build/rspress/
  cp /root/docs-rspress/_deploy/nginx.conf  /root/qiqi-mall/docs-build/rspress/nginx.conf
  cp /root/docsify/build-material.mjs       /root/qiqi-mall/docs-build/docsify/
  cp /root/docsify/_deploy/nginx.conf       /root/qiqi-mall/docs-build/docsify/nginx.conf
  cp /root/.qiqi-standing/docs-dev-refresh.sh /root/qiqi-mall/docs-build/

三个端口各是什么
  :3027  Rspress dev（88 页，含口语稿与 12 篇私人笔记）—— 日常看这份
  :3028  docsify（旧站原样，回退用）
  :3010  Rspress prod（74 页，无口语稿无私人笔记）—— 公网 docs.rose7.cn 发的就是这份
