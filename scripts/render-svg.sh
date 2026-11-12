#!/usr/bin/env bash
# render-svg.sh — 把 .mmd 渲染成「可直接嵌站点的 SVG」（全站图集统一管线）
#
# 用法:
#   scripts/render-svg.sh docs/er/1-ER图-商品域.mmd [更多 .mmd ...]
#   scripts/render-svg.sh --all             # 全仓 docs/**/*.mmd（串行，约 2~3 分钟）
#   scripts/render-svg.sh --config <路径> …  # 覆盖默认配置（默认 scripts/mermaid-svg.config.json）
#   scripts/render-svg.sh --help
# 输出: 与输入同名同目录的 .svg（覆盖原 svg；绝不碰同名 .png）
#
# ── 为什么这份脚本存在（踩过的坑，别重犯）────────────────────────────
#   1) 固化配置：2026-11 之前，生成 SVG 用的 mermaid 配置只存在于 /tmp/svg-cfg-C420.json
#      （207 字节），仓库里只有 render-diagram.sh（那条管线只出 PNG）。/tmp 一重启，
#      47 张 svg 就成了「能看，但复现不了」——被问「这些图怎么生成的」只能答
#      「一个已经消失的 tmp 文件」。所以配置固化成 scripts/mermaid-svg.config.json，
#      和本脚本一起进仓库：clone 之后一条命令即可复现全站图集。
#   2) 版本必须锁 v12.x（当前 12.2.0，内含 mermaid 12.3.0）：
#      - v11 渲出的 <style> 块 3301 字符、ER 宽度 2043；
#      - v12 是 5226 字符、宽度 1764（本仓库这批图就是 v12 渲的）。
#      版本不对不是「差一点」，是整批图外观变了。所以本脚本硬校验，版本不符直接报错退出，
#      绝不降级渲染、不静默成功。
#   3) 补根节点 width/height：mermaid 只输出 viewBox + width="100%"，浏览器里
#      img.naturalWidth 会退化成 300（默认替换尺寸），站点点击放大（Viewer.js）
#      就把图缩成 300×73 的缩略图。所以渲染后必须按 viewBox 四舍五入补根节点
#      width/height（用 python3 在写回前修，不留给人工）。
#      取整用 Python round()（银行家舍入），与这批既有图的历史取值一致：
#      1221.5→1222、1703.5→1704、2282.5→2282（若用 floor(x+0.5) 会得到 2283，与原图不符）。
#   4) 不重渲同名 .png：docs/**/*.png 是回滚保险。svg 与 png 是同一张图的两种产物，
#      重渲 png 会因 mermaid 版本/尺寸链差异改变外观，保险就失效了。
#      PNG 那条老管线（scripts/render-diagram.sh，精确 2352 宽）保留不动。
#   5) htmlLabels:false 的原因：Flowchart / ER 的标签若走 foreignObject + HTML，
#      中文在 Firefox / Safari 里会整块白屏（跨浏览器渲染差异 + 站点 CSP）。
#      关掉后文字落在 <text> 里，各浏览器一致。每张图渲完都自检 foreignObject 计数
#      必须为 0，不达标直接报错退出（fail-closed）。
#   6) 主题必须 redux-color（配置里显式写死）：这批图的调色板就是它——`<style>` 头是
#      font-size:14px + fill:#28253D，时序图参与者框是 redux 调色板（rgb(34,211,238) 一类）。
#      v12 的各类图默认主题恰好也是 redux-color，所以「不写 theme」也能出对图；
#      但一旦有人把它「简化」成 theme:"default"，`<style>` 会从 5226→3297、
#      fill #28253D→#333、字号 14→16px，全站图外观整体漂移（实测，见下）。
#      所以显式写死 + 版本锁，双重保险。
#   7) neo look 来自 v12 每类图的 `look:"neo"` 默认值（rough.js 手绘风），配置里没有也不该
#      有顶层 look 键——实测顶层 look 是无效果键。真正的保险是第 2 条的版本硬校验。
#   8) 底部 20px 保底必须**按墨迹算**，不能「viewBox 高度无脑 +20」：
#      mermaid 的 viewBox 是 layout 的几何包围盒，**不含文字下缘**（基线以下那截字身），
#      所以贴底的节点/标签会被切掉半截字（踩过：买家、管理员两组标签下缘被切）。
#      但反过来也不能每张图统一加高——那会给本来就有余量的图多压一条无意义白边，
#      而且真正被切的图该补多少还是不知道。正确做法：遍历 XML 累计 transform，
#      算出所有几何元素（rect/circle/ellipse/line/path/text…）在根坐标系里的最低 y
#      （文字按「基线 + 0.32em 字身下缘」估），只有「墨迹下缘 + 20px」越过 viewBox 下界时
#      才把下界扩到那里。算不出墨迹（解析失败 / 没有几何元素 / 数值明显不合理）一律
#      fail-closed 报错退出，不写回、不静默跳过。
#      口径注记（别把它当 bug 来"修"）：这里的 20px 是**几何墨迹**到 viewBox 下界的距离。
#      neo look 的节点框带 feDropShadow（dy=4 / flood-opacity 0.06），那层淡影会吃掉约 4px，
#      所以肉眼量到的纯白通常是 ≈19px、含淡影 ≈15px —— 这是投影，不是留白算少了，
#      也不要为了"凑够 20px 白边"去把 PAD 调大（那会把所有图都顶高一圈）。
#   9) 流程图里别给**竖直连线**挂 `|标签|`：mermaid 的 .edgeLabel 底色是
#      hsl(-120,0%,80%) 且 opacity:0.5，标签就压在连线中点上，箭头/虚线会从字中间穿过去
#      （实测：「系统自动拆，审核通过时执行」被箭头划过「审」字）。长注解宁可搬进
#      节点文本或子图标题——那里有实心背景，永远不会被线穿。
#
# ── 配置逐项审计（2026-11-12 实测：mermaid-cli 12.2.0 / mermaid 12.3.0）──────
#   键                              作用                        删掉会怎样
#   theme: "redux-color"            起作用                      <style> 5226→3297、色/字号变
#   themeVariables.fontFamily       起作用                      CJK 字体栈丢失
#   htmlLabels（顶层）= false        起作用                      ER 高 1296.75→1321.75、流程图 1150→1159
#   flowchart.htmlLabels = false    起作用                      同上（顶层与这层都要给）
#   flowchart.wrappingWidth = 420   起作用                      流程图宽 1237→907，节点不再折行
#   er.useMaxWidth = true           冗余（= v12 默认值）          无变化；保留以逐字对齐原始 /tmp 配置
#   sequence.wrappingWidth = 420    实测无影响                   无变化（v12.2.0 的 sequence 布局不读它）
#   结论：真正决定外观的是 theme + fontFamily + htmlLabels(两处) + flowchart.wrappingWidth，
#         其余两个键保留只为「和当初那批参数逐字一致」，删了也不影响现有 48 张。
#
# ── 验收怎么看（重要：不要看 md5）─────────────────────────────────
#   neo look 的连线/边框走 rough.js 随机种子，同一输入连渲两次字节不同
#   （只差手绘描边的贝塞尔控制点，肉眼无差，ER 图最明显：131 条 path 里约 73 条 d 不同，
#   但 139 个 <text> 与标签序列完全一致）。所以复现性验收看两件事：
#     - <style> 块是否逐字节相同  → 证明 mermaid 版本 + 配置一致
#     - viewBox 是否完全一致      → 证明布局/尺寸链一致
#   本脚本对每个文件都打印 style 字节数、viewBox、补上的 width/height，直接和改动前对一遍。
#   浮点尾差属正常：同一配置在不同 chrome 版本下文本度量可能差 1e-4 量级
#   （实测 chrome-headless-shell 156 vs 157：1810.5546875 1375.0001220703125 vs …1375），
#   验收允许 viewBox ±1 以内的尾差；取整后的 width/height 不受影响。
#
# ── 写 .mmd 时的坑（渲染前就该知道）──────────────────────────────
#   时序图的消息文本里不要写 <br/>：mermaid 会把它当成**新的一条消息**，还会多占一个序号，
#   图上出现一条无头无尾的乱入消息，排查起来很费时间。消息行只能保持短句，
#   需要换行/分段说明时用 `note ... <br/>`（note 里 <br/> 才是换行）。
#
#   流程图/ER 的标签（htmlLabels:false 模式）会把**半角空格当成断行点**：
#   `A4 -. 同步生成 N 个子发货单 .-> D1` 会渲成三行（「同步生成」/「N」/「个子发货单」），
#   中间那行只剩一个字母 N，就是典型的单字孤行。想控制行数就把空格换成 <br/>
#   （`同步生成<br/>N个子发货单`）；注意 <br/> 之后若有空格仍会在那里断行，
#   所以中英混排时通常要把空格一起去掉（只动空格/断行，不要动文字）。
#   同理，节点标题里用空格分隔的短语会被断成一列短行（如「人工调整 可选 仅待下发 …」渲成 9 行），
#   是同一个机制，属于写法问题而不是渲染 bug。
#
# ── 内存纪律（DSH cgroup 有 6G 上限，超了会假死）────────────────────
#   一次只渲一张，串行 + sleep；不要放进并行 for 里连发。
#   每张图用独立临时目录（mktemp -d），避免和别的智能体/并发任务抢同一个输出路径。
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEFAULT_CFG="$REPO_ROOT/scripts/mermaid-svg.config.json"
PINNED_CLI="@mermaid-js/mermaid-cli@12.2.0"   # 锁死：不要改成 @latest / v11
REQUIRED_MAJOR=12
CFG="$DEFAULT_CFG"

PUPPETEER_CFG="${PUPPETEER_CFG:-/tmp/puppeteer-cfg.json}"

usage() { sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

die() { echo "错误: $*" >&2; exit 1; }

# ---------- 参数 ----------
[[ $# -ge 1 ]] || { usage >&2; exit 2; }

MMDS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --all)
      while IFS= read -r f; do MMDS+=("$f"); done < <(find "$REPO_ROOT/docs" -name '*.mmd' -type f | sort)
      shift
      ;;
    --config)
      [[ $# -ge 2 ]] || die "--config 需要一个路径参数"
      CFG="$2"; shift 2
      ;;
    --config=*)
      CFG="${1#--config=}"; shift
      ;;
    -*)
      die "未知参数: $1（只支持 .mmd 路径、--all、--config <路径>）"
      ;;
    *)
      [[ -f "$1" ]] || die "文件不存在: $1"
      [[ "$1" == *.mmd ]] || die "只接受 .mmd 输入: $1"
      MMDS+=("$1"); shift
      ;;
  esac
done

# ---------- 前置检查：配置、mermaid-cli 及其版本 ----------
[[ -f "$CFG" ]] || die "找不到配置文件 $CFG（默认配置必须和本脚本一起在仓库里）"
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$CFG" >/dev/null 2>&1 \
  || die "配置文件不是合法 JSON: $CFG"
[[ ${#MMDS[@]} -gt 0 ]] || die "没有可渲染的 .mmd"

if [[ -n "${MMDC:-}" ]]; then
  # 允许显式指定二进制（例如 CI 里用预装的 node_modules/.bin/mmdc）
  MMDC_CMD=("$MMDC")
else
  MMDC_CMD=(npx --yes "$PINNED_CLI")
fi

command -v "${MMDC_CMD[0]}" >/dev/null 2>&1 \
  || die "找不到 ${MMDC_CMD[0]}（mermaid-cli 未安装/不在 PATH）。本脚本不会降级渲染。"

MMDC_VERSION=$("${MMDC_CMD[@]}" --version 2>/dev/null | tr -d '[:space:]') || true
[[ -n "$MMDC_VERSION" ]] || die "拿不到 mermaid-cli 版本号（${MMDC_CMD[*]} --version 无输出）。"
if [[ "${MMDC_VERSION%%.*}" != "$REQUIRED_MAJOR" ]]; then
  die "mermaid-cli 版本不符：实际=$MMDC_VERSION，需要 v${REQUIRED_MAJOR}.x（锁定 ${PINNED_CLI}）。
      v11 与 v12 渲出的 <style> 块和宽度不同，降级渲染会静默改变全站图外观，因此拒绝执行。
      修复：npm i -g ${PINNED_CLI}  或  MMDC=/path/to/mmdc-v12 scripts/render-svg.sh ..."
fi

# headless chrome：优先用环境变量，否则用 puppeteer 缓存里的 headless-shell
if [[ -z "${PUPPETEER_EXECUTABLE_PATH:-}" ]]; then
  CAND=$(find /root/.cache/puppeteer/chrome-headless-shell -type f -name chrome-headless-shell 2>/dev/null | sort | tail -1)
  [[ -n "$CAND" ]] && export PUPPETEER_EXECUTABLE_PATH="$CAND"
fi
[[ -f "$PUPPETEER_CFG" ]] || printf '%s\n' '{"args":["--no-sandbox","--disable-setuid-sandbox","--disable-dev-shm-usage"]}' > "$PUPPETEER_CFG"

echo "mermaid-cli $MMDC_VERSION  config=$CFG  文件数=${#MMDS[@]}"

# ---------- 逐张渲染（串行，一张一个临时目录） ----------
for mmd in "${MMDS[@]}"; do
  svg="${mmd%.mmd}.svg"
  [[ "$mmd" == *.mmd ]] || die "只接受 .mmd 输入: $mmd"
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/render-svg.XXXXXX") || die "mktemp 失败"

  # 1) 渲染到临时目录（不直接写目标位置，避免半成品/并发互相踩）
  if ! "${MMDC_CMD[@]}" -i "$mmd" -o "$tmp/out.svg" \
        -c "$CFG" -b white -p "$PUPPETEER_CFG" -q >"$tmp/mmdc.log" 2>&1; then
    cat "$tmp/mmdc.log" >&2 || true
    rm -rf "$tmp"
    die "渲染失败: $mmd"
  fi
  [[ -s "$tmp/out.svg" ]] || { cat "$tmp/mmdc.log" >&2 || true; rm -rf "$tmp"; die "mermaid 没有产出 svg: $mmd"; }

  # 2) 按「墨迹下缘」决定底部留白 + 补根节点 width/height（取 viewBox 四舍五入）+ 自检（fail-closed）
  #    为什么不是无脑 vbH+20：那样每张图都多一条白边，且真被切的图仍会被切。
  #    这里遍历 XML 累计 transform，算出所有几何元素（rect/circle/ellipse/line/path/text…）在
  #    根坐标系里的最低 y（文字按「基线 + 0.32em 字身下缘」估），只有「墨迹下缘 + 20px」超过
  #    viewBox 下界时才把下界扩到那个位置。算不出来（解析失败/没有几何元素/数值不合理）直接失败退出。
  report=$(python3 - "$tmp/out.svg" "$svg" <<'PY'
import math
import re
import sys
import xml.etree.ElementTree as ET

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()

m_style = re.search(r'<style>(.*?)</style>', s, re.S)
if not m_style or not m_style.group(1).strip():
    sys.exit("[FAIL] 没有 <style> 块（mermaid 输出异常）")

i = s.find('<svg')
if i < 0:
    sys.exit("[FAIL] 找不到 <svg> 根节点")
j = s.find('>', i)
root = s[i:j + 1]

vb = re.search(r'viewBox="\s*([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)\s*"', root)
if not vb:
    sys.exit("[FAIL] 根节点没有 viewBox")
x, y, vbw, vbh = (float(v) for v in vb.groups())
if vbw <= 0 or vbh <= 0:
    sys.exit(f"[FAIL] viewBox 尺寸非正: {vbw}x{vbh}")

# ---------------- 墨迹下缘：累计 transform，取所有元素在根坐标系里的最大 y ----------------
PAD = 20.0          # 底部保底留白（px）
DESCENT = 0.32      # 字身下缘 ≈ 基线 + 0.32em（CJK 取保守值）
DEFAULT_FS = 14.0   # 与 scripts/mermaid-svg.config.json 的 font-size 一致
NUM = r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?'
SKIP_TAGS = {'defs', 'marker', 'style', 'filter', 'clipPath', 'mask', 'pattern', 'symbol',
             'linearGradient', 'radialGradient', 'title', 'desc'}
IDENT = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)   # a b c d e f: x' = ax+cy+e, y' = bx+dy+f


def local(tag):
    return tag.rsplit('}', 1)[-1] if '}' in tag else tag


def mul(m1, m2):
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (a1 * a2 + c1 * b2, b1 * a2 + d1 * b2,
            a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1)


def parse_transform(val):
    m = IDENT
    for name, args in re.findall(r'([a-zA-Z]+)\s*\(([^)]*)\)', val or ''):
        v = [float(t) for t in re.findall(NUM, args)]
        name = name.strip().lower()
        if name == 'translate':
            t = (1.0, 0.0, 0.0, 1.0, v[0] if v else 0.0, v[1] if len(v) > 1 else 0.0)
        elif name == 'scale':
            sx = v[0] if v else 1.0
            sy = v[1] if len(v) > 1 else sx
            t = (sx, 0.0, 0.0, sy, 0.0, 0.0)
        elif name == 'matrix' and len(v) == 6:
            t = tuple(v)
        elif name == 'rotate':
            a = math.radians(v[0] if v else 0.0)
            ca, sa = math.cos(a), math.sin(a)
            t = (ca, sa, -sa, ca, 0.0, 0.0)
            if len(v) == 3:                     # rotate(a cx cy)
                t = mul(mul((1.0, 0.0, 0.0, 1.0, v[1], v[2]), t),
                        (1.0, 0.0, 0.0, 1.0, -v[1], -v[2]))
        elif name == 'skewx':
            t = (1.0, 0.0, math.tan(math.radians(v[0] if v else 0.0)), 1.0, 0.0, 0.0)
        elif name == 'skewy':
            t = (1.0, math.tan(math.radians(v[0] if v else 0.0)), 0.0, 1.0, 0.0, 0.0)
        else:
            continue
        m = mul(m, t)
    return m


def num_of(v, fs, default=0.0):
    if v is None:
        return default
    v = str(v).strip()
    mv = re.fullmatch(r'(?P<num>' + NUM + r')\s*(?P<unit>em|px)?', v)
    if not mv:
        return default
    val = float(mv.group('num'))
    if mv.group('unit') == 'em':
        val *= fs
    return val


def path_points(d):
    """把 path d 里的坐标点全取出来（用于估计下限；控制点也算，宁大不小）"""
    pts = []
    cur = (0.0, 0.0)
    for cmd, args in re.findall(r'([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)', d or ''):
        v = [float(t) for t in re.findall(NUM, args)]
        k = 0
        while True:
            if cmd in 'Mm':
                if k + 2 > len(v):
                    break
                px, py = v[k], v[k + 1]
                k += 2
                if cmd == 'm':
                    px += cur[0]
                    py += cur[1]
                cur = (px, py)
                pts.append(cur)
                cmd = 'l' if cmd == 'm' else 'L'      # 后续隐式 lineto
            elif cmd in 'Ll':
                if k + 2 > len(v):
                    break
                px, py = v[k], v[k + 1]
                k += 2
                if cmd == 'l':
                    px += cur[0]
                    py += cur[1]
                cur = (px, py)
                pts.append(cur)
            elif cmd in 'Hh':
                if k + 1 > len(v):
                    break
                px = v[k]
                k += 1
                if cmd == 'h':
                    px += cur[0]
                cur = (px, cur[1])
                pts.append(cur)
            elif cmd in 'Vv':
                if k + 1 > len(v):
                    break
                py = v[k]
                k += 1
                if cmd == 'v':
                    py += cur[1]
                cur = (cur[0], py)
                pts.append(cur)
            elif cmd in 'CcSsQqTt':
                n = 6 if cmd in 'Cc' else (4 if cmd in 'SsQq' else 2)
                if k + n > len(v):
                    break
                rel = cmd.islower()
                seg = v[k:k + n]
                k += n
                for q in range(0, n, 2):
                    px, py = seg[q], seg[q + 1]
                    if rel:
                        px += cur[0]
                        py += cur[1]
                    pts.append((px, py))
                cur = pts[-1]
            elif cmd in 'Aa':
                if k + 7 > len(v):
                    break
                seg = v[k:k + 7]
                k += 7
                px, py = seg[5], seg[6]
                if cmd == 'a':
                    px += cur[0]
                    py += cur[1]
                cur = (px, py)
                pts.append(cur)
            else:
                break
            if k >= len(v):
                break
    return pts


def local_points(el, tag, fs):
    """元素在自身坐标系里的采样点（只需要能挤出下缘的那些）"""
    pts = []
    if tag in ('rect', 'image', 'use', 'foreignObject'):
        ex, ey = num_of(el.get('x'), fs), num_of(el.get('y'), fs)
        ew, eh = num_of(el.get('width'), fs), num_of(el.get('height'), fs)
        pts += [(ex, ey), (ex + ew, ey + eh)]
    elif tag == 'circle':
        cx, cy, r = num_of(el.get('cx'), fs), num_of(el.get('cy'), fs), num_of(el.get('r'), fs)
        pts += [(cx - r, cy - r), (cx + r, cy + r)]
    elif tag == 'ellipse':
        cx, cy = num_of(el.get('cx'), fs), num_of(el.get('cy'), fs)
        rx, ry = num_of(el.get('rx'), fs), num_of(el.get('ry'), fs)
        pts += [(cx - rx, cy - ry), (cx + rx, cy + ry)]
    elif tag == 'line':
        pts += [(num_of(el.get('x1'), fs), num_of(el.get('y1'), fs)),
                (num_of(el.get('x2'), fs), num_of(el.get('y2'), fs))]
    elif tag in ('polygon', 'polyline'):
        v = [float(t) for t in re.findall(NUM, el.get('points') or '')]
        pts += list(zip(v[0::2], v[1::2]))
    elif tag == 'path':
        pts += path_points(el.get('d'))
    elif tag in ('text', 'tspan'):
        # 文字按基线 + 字身下缘估；mermaid 逐行用 <tspan y="..em" dy="..em"> 排版，子行基线要累加
        base = num_of(el.get('y'), fs) + num_of(el.get('dy'), fs)
        pts.append((num_of(el.get('x'), fs), base + DESCENT * fs))
        for ch in list(el):
            if local(ch.tag) in ('tspan', 'textPath'):
                cbase = base + num_of(ch.get('y'), fs) + num_of(ch.get('dy'), fs)
                pts.append((num_of(ch.get('x'), fs), cbase + DESCENT * fs))
    return pts


ink = {'bottom': None}


def visit(el, ctm, fs):
    tag = local(el.tag)
    if tag in SKIP_TAGS:
        return
    st = el.get('style') or ''
    mfs = re.search(r'font-size:\s*([\d.]+)px', st)
    if mfs:
        fs = float(mfs.group(1))
    if el.get('font-size'):
        fs = num_of(el.get('font-size'), fs, fs)
    m = mul(ctm, parse_transform(el.get('transform')))
    for px, py in local_points(el, tag, fs):
        ay = m[1] * px + m[3] * py + m[5]
        if ink['bottom'] is None or ay > ink['bottom']:
            ink['bottom'] = ay
    for ch in list(el):
        visit(ch, m, fs)


try:
    tree = ET.fromstring(s)
except ET.ParseError as e:
    sys.exit(f"[FAIL] SVG 解析失败，无法计算墨迹下缘: {e}")

visit(tree, IDENT, DEFAULT_FS)

ink_bottom = ink['bottom']
if ink_bottom is None:
    sys.exit("[FAIL] 无法计算墨迹下缘（未识别到任何几何元素），拒绝写回")
if not math.isfinite(ink_bottom):
    sys.exit(f"[FAIL] 墨迹下缘非有限值({ink_bottom})，拒绝写回")

vb_bottom = y + vbh
need = (ink_bottom + PAD) - vb_bottom
if need > 0:
    if need > max(400.0, vbh):
        sys.exit(f"[FAIL] 墨迹下缘 {ink_bottom:.1f} 超出 viewBox 下界 {vb_bottom:.1f} 达 {need:.1f}px"
                 f"（超过兜底阈值），疑似解析异常，拒绝写回")
    vbh2 = vbh + need
    pad_note = f"底部扩了 {need:.1f}px"
else:
    vbh2 = vbh
    pad_note = f"原有底部留白 {vb_bottom - ink_bottom:.1f}px ≥ {PAD:g}px，无需扩"

# 银行家舍入，和这批既有图的历史取值一致（见文件头第 3 条）
w, h = round(vbw), round(vbh2)
if w <= 0 or h <= 0:
    sys.exit(f"[FAIL] 取整后尺寸非正: {w}x{h}")

new_vb = f"{x:g} {y:g} {vbw:g} {vbh2:g}"
new_root = re.sub(r'viewBox="[^"]*"', f'viewBox="{new_vb}"', root, count=1)
# width: 有就替换（mermaid 默认 width="100%"），没有就插到 <svg 之后
if re.search(r'\bwidth="[^"]*"', new_root):
    new_root = re.sub(r'\bwidth="[^"]*"', f'width="{w}"', new_root, count=1)
else:
    new_root = '<svg width="%d"' % w + new_root[4:]
# height: mermaid 一般不给，缺失就插
if re.search(r'\bheight="[^"]*"', new_root):
    new_root = re.sub(r'\bheight="[^"]*"', f'height="{h}"', new_root, count=1)
else:
    new_root = '<svg height="%d"' % h + new_root[4:]
new_root = re.sub(r'max-width:\s*[0-9.]+px', f'max-width: {w}px', new_root, count=1)

s = s[:i] + new_root + s[j + 1:]

# ---- 自检（fail-closed：任何一项不达标就整体失败，不写回） ----
errs = []
fo = s.count('foreignObject')
if fo != 0:
    errs.append(f"foreignObject={fo}（必须为 0，否则 Firefox/Safari 中文会白屏）")
root2 = s[s.find('<svg'):s.find('>', s.find('<svg')) + 1]
wm = re.search(r'\bwidth="([^"]+)"', root2)
hm = re.search(r'\bheight="([^"]+)"', root2)
if not wm or not wm.group(1).isdigit():
    errs.append(f'根节点 width 缺失或非整数: {wm.group(1) if wm else None}')
elif int(wm.group(1)) != w:
    errs.append(f'根节点 width={wm.group(1)} 与 viewBox 取整 {w} 不一致')
if not hm or not hm.group(1).isdigit():
    errs.append(f'根节点 height 缺失或非整数: {hm.group(1) if hm else None}')
elif int(hm.group(1)) != h:
    errs.append(f'根节点 height={hm.group(1)} 与 viewBox 取整 {h} 不一致')
vb2 = re.search(r'viewBox="([^"]+)"', root2)
if not vb2 or vb2.group(1).split() != new_vb.split():
    errs.append(f'根节点 viewBox 未按预期写回: {vb2.group(1) if vb2 else None} != {new_vb}')
if errs:
    sys.exit("[FAIL] " + "; ".join(errs))

open(dst, 'w', encoding='utf-8').write(s)
print(f"style={len(m_style.group(1))} viewBox={new_vb} size={w}x{h} foreignObject={fo} "
      f"墨迹下缘={ink_bottom:.1f} 底部实留={y + vbh2 - ink_bottom:.1f}px（{pad_note}）")
PY
) || { rm -rf "$tmp"; echo "自检失败（未写回）: $mmd" >&2; exit 1; }

  rm -rf "$tmp"
  echo "  OK  $svg"
  echo "      $report"

  # 串行 + 间隔，避免 chrome 叠加吃内存
  if [[ ${#MMDS[@]} -gt 1 ]]; then sleep 1; fi
done

echo "完成：${#MMDS[@]} 张。验收看上面的 style/viewBox（neo look 的字节级差异属正常，见文件头）。"
