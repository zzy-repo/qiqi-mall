/**
 * data-testid 选择器小工具 —— e2e 定位一律从这里走，别再拼 DOM 结构。
 *
 * 为什么要有这个文件：
 * 以前用例靠 `getByRole('button', { name: '新增' })`、`.el-form-item` 过滤中文 label 这类方式定位，
 * 改文案、调布局、加一列就会断。现在关键交互元素都在模板上带了 `data-testid`，
 * 选择器稳定、可自解释，调试时也不用反复试 DOM。
 *
 * 用法：
 *   tid(page, 'product-list-create')                        // 单个元素
 *   tid(page, `orders-row-${orderSn}`)                      // 动态行（订单号/SKU id/实体 id）
 *   tid(page, 'product-row-edit').nth(i)                     // 多个同名（每行一个）时按序号取
 *   tidOf(page, (id) => `home-category-${id}`, catId)         // 名字要算出来时
 *   tidNth(page, 'delivery-row-edit', 1)                     // 同上，简写
 *   rowTid(page, 'cart-row', skuId)                          // `<前缀>-<id>` 行锚点
 *   tidPrefix(page, 'product-detail-sku-')                   // 前缀匹配（不知道 id 时枚举）
 *
 * 快照（不要在这里写死业务数据）：testid 全部是「页面-元素[-限定]」的静态串，
 * 或 `行-<实体id>` 形式，具体 id 一律由用例从接口数据里取，不要硬编码。
 *
 * ---------------------------------------------------------------------------
 * 未覆盖清单：这些位置拿不到 data-testid，只能按下面的写法兜底（都有原因）
 * ---------------------------------------------------------------------------
 * 1) ElMessageBox（确认框）的按钮 —— `确定 / 删除 / 取消`
 *    弹窗由 ElMessageBox 内部渲染，按钮不在任何模板里。
 *    写法：`page.locator('.el-message-box__btns').getByRole('button', { name: '确定' })`
 *    注意：**高危写操作已经不弹 ElMessageBox**，统一走 DangerConfirmDialog
 *    （`danger-confirm-dialog/-willdo/-ok/-cancel/-error`，见 support/danger-ui.js），
 *    那套有稳定 testid。ElMessageBox 只剩分类/类型/管理员/仓库这类「低危删除」还在用。
 *
 * 2) 订单审核的确认弹窗 —— 已合并进 DangerConfirmDialog
 *    原来审核是「审核」+ ElMessageBox（通过/不通过）+ 独立「查看拆单方案」弹窗，都没有 testid；
 *    现在拆成两个行按钮 `order-row-audit-pass` / `order-row-audit-reject`，点下去直接进
 *    统一确认弹窗，拆单方案渲染在它的 `danger-confirm-details` 明细区，全部可断言。
 *
 * 3) el-select / el-tree-select / el-dropdown 的**浮层选项**
 *    下拉面板 teleport 到 body、选项由组件渲染。
 *    写法：先点触发器 testid（如 `product-form-type`），再
 *          `page.locator('.el-select-dropdown__item:visible').filter({ hasText: '手机' })`
 *
 * 4) el-table 的 `<tr>` 行本身
 *    `tr` 由 el-table 内部渲染，模板只够得着单元格内容。所以：
 *      · 行锚点 `<实体>-row-<id>` 落在「操作」单元格内容上（行内按钮都在里面，可按行点按钮）；
 *      · 需要「整行文本」或读写非操作列时，仍按行文本过滤：
 *        `page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()`
 *      这是本套件唯一保留的大面积结构选择器，缺的行锚点见第 5 条。
 *
 * 5) prop-only 表格列（没有 `#default` 插槽）无法挂属性
 *    已补：`sku-row-stock`。其余列若后续要 id，需要给该列加 `#default` 插槽（轻微结构改动）。
 *
 * 6) ImageUploader 组件内部（`*-cover-upload` / `*-cover-preview` 之类）
 *    在 `components/ImageUploader.vue` 里，不在页面模板上。
 *    写法：外层容器 testid + 内部原生控件，如
 *          `tid(page, 'product-form-cover').locator('input').first()`
 *
 * 7) 表单校验错误文本 `.el-form-item__error`
 *    el-form-item 内部渲染。登录页把锚点落在 form-item 根节点上：
 *          `tid(page, 'auth-error-username').locator('.el-form-item__error')`
 *    其它弹窗（仓库/订单等）同理，用所在弹窗的 testid 再 `.locator('.el-form-item__error')`。
 *
 * 8) toast 提示（`.el-message--success/error`）与空态里的 el-table 自带文案
 *    提示组件没有模板锚点，继续用 class：
 *          `page.locator('.el-message--success').first()`
 *
 * 9) 订单「新增/编辑」弹窗里的明细表格（选商品/选规格/单价/数量）
 *    那两个 el-select 与 input-number 在表格行内，这一版没上 testid。
 *    写法：`dlg.locator('.el-table__body tr').nth(i).locator('.el-select').first()`（结构兜底）
 *
 * 10) 侧边栏折叠按钮、面包屑分隔符、"模拟WMS回传"浮层里的 `<li>` 这类纯装饰/内部件
 *     不加 testid，也不该断言它们；需要断言菜单入口时用 `nav-<key>`。
 */
import { expect } from '@playwright/test'

/** 单个 testid 元素 */
export const tid = (root, name) => root.getByTestId(name)

/** 名字需要现算时用：tidOf(page, (id) => `home-category-${id}`, catId) */
export const tidOf = (root, fn, ...args) => root.getByTestId(fn(...args))

/** 同名多个（每行一个）时按序号取：tidNth(page, 'product-row-edit', 2) */
export const tidNth = (root, name, index) => root.getByTestId(name).nth(index)

/** 行锚点：rowTid(page, 'cart-row', skuId) → getByTestId(`cart-row-${skuId}`) */
export const rowTid = (root, prefix, id) => root.getByTestId(`${prefix}-${id}`)

/** 前缀匹配，用来枚举动态 id 的集合：tidPrefix(page, 'product-detail-sku-') */
export const tidPrefix = (root, prefix) => root.locator(`[data-testid^="${prefix}"]`)

/** 断言某 testid 存在（用于「入口应消失」类断言） */
export const expectNoTid = async (root, name, message) => {
  await expect(root.getByTestId(name), message).toHaveCount(0)
}

/** 断言某 testid 可见 */
export const expectTidVisible = async (root, name, message) => {
  await expect(root.getByTestId(name), message).toBeVisible()
}
