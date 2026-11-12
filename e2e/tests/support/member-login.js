/**
 * 会员登录名（演示用的「像真实账号」的登录名）。
 *
 * 为什么单独一个文件而不是塞进 fixtures.js：这个 helper 是给「商家侧履约/账号」几个场景用的，
 * 而 fixtures.js 当时正被另一路改动同时编辑，独立成文件可以让两边各自提交、互不覆盖。
 *
 * 为什么登录名也要「自然化」：
 *   后台「订单列表 / 新增订单」的**买家列直接显示 username**，买家侧注册表单里也是明文输入，
 *   所以登录名属于「界面可见文案」——不能带 `e2e_` 前缀，也不能是裸时间戳。
 * 命名口径：「拼音名 + 3 位序号 + 3 位随机数」，像真实会员号；唯一性只靠数字，
 *   断言里不要拿「界面显示名含某个标记」来定位元素（用 data-testid 行锚点或先查 id）。
 */
import { pick } from './fixtures.js'

/** 拼音基名（和 fixtures 里 memberNicks 的中文昵称对得上，读起来像同一个人） */
export const loginNameBases = [
  'linwan', 'shenzhiyao', 'chengxubai', 'sunian', 'guqinghe', 'yexishen', 'xuxingye', 'wenyining'
]

/** 序号是必需的：同一毫秒内连续两次调用只靠时间戳会撞名（后端唯一键会拒绝） */
let seq = 0

/**
 * 生成一个自然登录名，例如 `guqinghe000933`。
 * @param seed 只决定用哪个拼音基名，可取 stamp()/任意唯一键
 */
export function memberLoginOf(seed) {
  const base = pick(loginNameBases, seed ?? Date.now())
  return `${base}${String(seq++ % 1000).padStart(3, '0')}${Math.floor(Math.random() * 900 + 100)}`
}
