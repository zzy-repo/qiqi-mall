/**
 * 数据准备 —— 只允许放「页面上根本没有入口」的准备动作。
 *
 * 当前清单：**空**。
 * 上一轮已逐一确认，五个业务场景需要的准备动作 admin/portal 都有真实入口，已改走
 * support/ui.js（例如给 SKU 补库存：admin「SKU 管理」页 →「编辑」弹窗 →「库存」）：
 *   补库存        → ui.js  adminSetSkuStock()
 *   注册 / 登录   → ui.js  memberRegisterAndLogin()、adminLogin()
 *   加购 / 下单 / 支付 → ui.js  addSpecToCart()、submitCartOrder()
 *   审核 / 发货单下发 / WMS 回传 → ui.js  adminSearchOrderRow()、openOrderDrawer()、clickDispatch()、clickWmsCallback()
 *   确认收货 / 申请退货 → 场景用例内真实点击
 *
 * 规则（新增准备动作前必须自查）：
 * 1. 先确认页面上确实没有入口（打开页面走一遍，别凭代码猜）；
 * 2. 只有「页面无入口」才允许放这里，并在函数注释里写明「页面无入口，仅补数据」；
 * 3. 不允许在这里推进业务状态（下单、支付、审核、发货、确认收货、退货审核……）；
 * 4. 业务操作一律走 ./ui.js，断言读取一律走 ./assert.js。
 */

// 当前没有任何「页面无入口」的数据准备动作，所以本文件暂无导出。
// 将来真要加，按上面 4 条规则在这里逐个 export，并在文件头注释的规则说明里登记。
