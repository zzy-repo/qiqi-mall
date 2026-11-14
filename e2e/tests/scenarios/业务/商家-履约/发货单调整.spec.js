import { test, expect } from '../../../support/test.js'
import {
  adminLoginHardened,
  gotoOrderList,
  createOrderViaUi,
  searchOrderRow,
  auditPassOrder,
  openOrderDrawer,
  deliveryRowBySn,
  clickDeliveryAction,
  setDialogQty,
  submitDeliveryDialog,
  pickMergeTarget,
  submitMergeDialog,
  expectToastSuccess,
  warehouseCellOf,
  pickWarehouseExcluding,
  deliveryDialog,
  // 预演失败 / 409 两条新路径用到的弹窗定位器（全站单例，见 support/danger-ui.js）
  dangerDialog,
  conflictDialog,
  conflictRefresh,
  confirmDanger
} from '../../../support/admin-ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi, listDeliveriesApi } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import { receiverOf } from '../../../support/fixtures.js'
import { API } from '../../../support/env.js'

/**
 * 业务 / 商家-履约 / 发货单调整（docs/flows/02-业务场景/业务/商家-履约/发货单调整/1-业务时序图.mmd）
 *
 * 覆盖「待下发」阶段的人工干预全链路：改仓 → 改量（减/加）→ 拆分 → 合并 → 删除（必须并入别处）
 * 以及守卫：下发之后干预入口全部消失（只能回传/取消）。
 *
 * 数据准备全部走 admin 真实 UI：新增订单（状态=待审核）→ 审核通过 → 自动拆出 2 个待下发包裹。
 * 商品名与仓库名都从接口实时读，不写死（dev 库与测试库种子不同，写死必挂）。
 */
test.describe('业务 › 商家-履约 › 发货单调整', () => {
  test('待下发包裹可改仓/改量/拆分/合并/删除，下发后入口消失', async ({ page, request }) => {
    let productName
    let skuCount
    await test.step('① 管理员登录并取商品信息', async () => {
      await adminLoginHardened(page)
      const { product, skus } = await getProductApi(request, 1)
      productName = product.name
      skuCount = skus.length
      expect(skuCount, '商品1 至少要有 2 个 SKU 才能拆成两个仓').toBeGreaterThanOrEqual(2)
    })

    let orderSn
    let table
    const orderSnStamp = `adj-${Date.now()}`
    await test.step('② 造一笔待审核订单（同一商品两个 SKU，跨两个仓）', async () => {
      await gotoOrderList(page)
      orderSn = await createOrderViaUi(page, {
        receiver: receiverOf(orderSnStamp),
        status: '待审核',
        items: [
          { productName, skuIndex: 0, quantity: 2 },
          { productName, skuIndex: 1, quantity: 1 }
        ]
      })
      await expect(page.locator('.el-table__body tr').first(), '新订单应为待审核').toContainText('待审核')
    })

    let snA, snB, whA, whB
    await test.step('③ 审核通过 → 自动拆出 2 个待下发包裹', async () => {
      const row = await searchOrderRow(page, orderSn)
      await auditPassOrder(page, row)
      await expect(row.getByTestId('order-row-status'), '审核后订单应为待发货').toContainText('待发货')
      const row2 = await searchOrderRow(page, orderSn)
      table = await openOrderDrawer(page, row2)
      const rows = table.locator('.el-table__body tr')
      await expect(rows, '应拆出 2 个包裹').toHaveCount(2)
      await expect(table).toContainText('待下发')
      await expect(table, '两个包裹默认都是自动拆单').toContainText('自动拆单')
      snA = (await rows.first().locator('td').first().innerText()).trim()
      snB = (await rows.last().locator('td').first().innerText()).trim()
      whA = await warehouseCellOf(rows.first())
      whB = await warehouseCellOf(rows.last())
      expect(whA, '两个包裹应从不同的仓发出').not.toEqual(whB)
    })

    let newWh
    await test.step('④ 编辑：把第一个包裹改到一个新的仓 → 来源变「人工调整」', async () => {
      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      newWh = await pickWarehouseExcluding(page, [whA, whB])
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      await expect(deliveryRowBySn(table, snA), `改仓后应显示 ${newWh}`).toContainText(newWh)
      await expect(deliveryRowBySn(table, snA), '人工改过的包裹应标记人工调整').toContainText('人工调整')
    })

    await test.step('⑤ 编辑：改量 2 → 1 再改回 2，后端数量跟着变', async () => {
      const token = await adminTokenOf(page)
      const order = await getAdminOrderBySnApi(request, token, orderSn)

      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      await setDialogQty(page, 0, 1)
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      let list = await listDeliveriesApi(request, token, order.id)
      let pkg = list.find((d) => d.deliverySn === snA)
      expect(pkg.items[0].quantity, '改量后后端数量应为 1').toBe(1)
      expect(pkg.source, '人工改过的包裹 source=1').toBe(1)

      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      await setDialogQty(page, 0, 2)
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      list = await listDeliveriesApi(request, token, order.id)
      pkg = list.find((d) => d.deliverySn === snA)
      expect(pkg.items[0].quantity, '再改回 2 后后端数量应为 2').toBe(2)
    })

    await test.step('⑥ 拆分：从该包裹拆 1 件到新包裹（沿用原仓）', async () => {
      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '拆分')
      await setDialogQty(page, 0, 1)
      await submitDeliveryDialog(page)
      await expectToastSuccess(page)
      await expect(table.locator('.el-table__body tr'), '拆分后应变成 3 个包裹').toHaveCount(3)
      await expect(table, '拆出来的新包裹也是人工调整').toContainText('人工调整')
    })

    await test.step('⑦ 合并：把新拆出来的包裹并入同仓包裹，被合并的消失', async () => {
      const rows = table.locator('.el-table__body tr')
      const newRow = rows.last()                            // 新拆出来的包裹排在最后
      const newSn = (await newRow.locator('td').first().innerText()).trim()
      const targetRow = rows.first()
      const targetSn = (await targetRow.locator('td').first().innerText()).trim()
      await clickDeliveryAction(page, newRow, '合并')
      await pickMergeTarget(page, targetSn)
      await submitMergeDialog(page)
      await expectToastSuccess(page)
      await expect(table.locator('.el-table__body tr'), '合并后应剩 2 个包裹').toHaveCount(2)
      await expect(table, `被合并掉的包裹 ${newSn} 不应再出现`).not.toContainText(newSn)
    })

    await test.step('⑧ 删除：有明细的包裹必须选并入目标（等同合并到别处）', async () => {
      const rows = table.locator('.el-table__body tr')
      const victimRow = rows.first()
      const victimSn = (await victimRow.locator('td').first().innerText()).trim()
      const keepRow = rows.last()
      const keepSn = (await keepRow.locator('td').first().innerText()).trim()

      await clickDeliveryAction(page, victimRow, '删除')
      await expect(tid(page, 'delivery-merge-dialog'), '有明细时删除要指定并入目标').toBeVisible()
      await pickMergeTarget(page, keepSn)
      await submitMergeDialog(page)
      await expectToastSuccess(page, '已删除')
      await expect(table.locator('.el-table__body tr'), '删除后只剩 1 个包裹').toHaveCount(1)
      await expect(table, `被删掉的包裹 ${victimSn} 不应再出现`).not.toContainText(victimSn)
    })

    await test.step('⑨ 下发后：干预入口全部消失（只能回传/取消）', async () => {
      const row = table.locator('.el-table__body tr').first()
      await clickDeliveryAction(page, row, '下发')
      await expect(row.getByTestId('delivery-row-status'), '下发后应为已下发').toContainText('已下发')
      for (const [name, testId] of [
        ['编辑', 'delivery-row-edit'],
        ['拆分', 'delivery-row-split'],
        ['合并', 'delivery-row-merge'],
        ['删除', 'delivery-row-remove']
      ]) {
        await expect(row.getByTestId(testId), `已下发不能${name}`).toHaveCount(0)
      }
      await expect(row.getByTestId('delivery-row-cancel'), '已下发也不能取消').toHaveCount(0)
      await expect(row.getByTestId('delivery-row-wms-callback'), '只剩回传入口').toBeVisible()
    })
  })

  /**
   * 取商品与 SKU。
   * 与 adminLoginHardened 同一处理口径：**后端刚启动 / 分片库正被重置时，首个 GET 可能瞬时失败**
   * （表被 TRUNCATE 的瞬间会返回 code=1、data=null），这不是被测业务缺陷，重试两次即可；
   * 三次都拿不到才抛错，绝不掩盖真实故障。
   */
  const firstProduct = async (request) => {
    for (let i = 0; ; i++) {
      const data = await getProductApi(request, 1)
      if (data?.product && data?.skus?.length) return data
      if (i >= 2) throw new Error('商品接口连续 3 次没返回商品数据（不是本用例的断言对象，先查环境）')
      await new Promise((r) => setTimeout(r, 1000))
    }
  }

  /**
   * 造一笔「待审核」订单 → 审核通过 → 拿到 2 个待下发包裹（数据全部自造，不依赖种子订单）。
   * @returns {{orderSn:string, orderId:number, table:import('@playwright/test').Locator,
   *            snA:string, pkgA:any, token:string}}
   */
  const prepareTwoPendingPackages = async (page, request, productName, seed) => {
    await gotoOrderList(page)                        // 「新增」按钮在订单列表页头
    const orderSn = await createOrderViaUi(page, {
      receiver: receiverOf(seed),
      status: '待审核',
      items: [
        { productName, skuIndex: 0, quantity: 2 },
        { productName, skuIndex: 1, quantity: 1 }
      ]
    })
    const row = await searchOrderRow(page, orderSn)
    await auditPassOrder(page, row)
    const table = await openOrderDrawer(page, await searchOrderRow(page, orderSn))
    const rows = table.locator('.el-table__body tr')
    await expect(rows, '审核通过应拆出 2 个待下发包裹').toHaveCount(2)
    const snA = (await rows.first().locator('td').first().innerText()).trim()
    const token = await adminTokenOf(page)
    const order = await getAdminOrderBySnApi(request, token, orderSn)
    const list = await listDeliveriesApi(request, token, order.id)
    const pkgA = list.find((d) => d.deliverySn === snA)
    expect(pkgA, `接口里应能查到包裹 ${snA}`).toBeTruthy()
    return { orderSn, orderId: order.id, table, snA, pkgA, token }
  }

  /**
   * 预演失败不收起表单（`delivery-form-preview-error`）。
   *
   * 场景是真实可发生的：A 管理员打开「编辑包裹」正在改仓改量，B 管理员同时把这个包裹下发了。
   * A 点确定 → 预演（PUT ?dryRun=true）被后端守卫拦下（只有待下发可以编辑）→
   * 表单必须原地不动、已填的仓与数量一个都不能丢，原因写在表单里（不是只发一条会消失的 toast）。
   */
  test('编辑弹窗预演失败：表单不收起、原因写在表单里、已填的仓与数量不丢', async ({ page, request }) => {
    test.setTimeout(180_000)
    await adminLoginHardened(page)
    const { product } = await firstProduct(request)

    let ctx
    await test.step('① 造单 → 审核通过 → 2 个待下发包裹', async () => {
      ctx = await prepareTwoPendingPackages(page, request, product.name, `pf-${Date.now()}`)
    })

    const dlg = deliveryDialog(page)
    let pickedWh
    let targetQty
    let currentWh
    await test.step('② 打开编辑弹窗，改发货仓 + 改明细数量（留着不提交）', async () => {
      const { table, snA, pkgA } = ctx
      currentWh = pkgA.warehouseName
      const currentQty = pkgA.items[0].quantity
      targetQty = currentQty === 1 ? 2 : 1
      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      await expect(dlg, '编辑弹窗应打开').toBeVisible()
      await setDialogQty(page, 0, targetQty)
      pickedWh = await pickWarehouseExcluding(page, [currentWh])
      await expect(tid(page, 'delivery-form-warehouse'), '发货仓应已选中新仓').toContainText(pickedWh)
    })

    await test.step('③ 另一个操作员把该包裹下发了（页面上已无入口，只能走接口并发）', async () => {
      // 说明：这不是「用接口推进业务状态」的捷径 —— 该包裹在**本页**已经打开编辑弹窗，
      // 页面上的「下发」入口此刻不可用（弹窗盖在上面），并发方必须来自另一个客户端。
      const res = await request.post(`${API}/admin/deliveries/${ctx.pkgA.id}/dispatch`, {
        headers: { Authorization: `Bearer ${ctx.token}` }
      })
      expect(res.status(), '并发下发应成功').toBe(200)
      const delivered = (await listDeliveriesApi(request, ctx.token, ctx.orderId))
        .find((d) => d.id === ctx.pkgA.id)
      expect(delivered.status, '并发方下发后包裹应为已下发(1)').toBe(1)
    })

    await test.step('④ 点确定 → 预演失败：表单还在、原因在表单里、已填内容全在', async () => {
      const previewResp = page.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes('/admin/deliveries/') && r.url().includes('dryRun'),
        { timeout: 20_000 }
      )
      await tid(page, 'delivery-form-submit').click()
      const resp = await previewResp
      const body = await resp.json()

      // 服务端原话（DeliveryEditService#requirePending）
      expect(body.code, '预演失败的业务码').toBe(1)
      expect(body.message, '预演失败的服务端原话')
        .toBe('只有待下发包裹可以编辑（当前状态 1，已下发的只能取消）')

      // 表单必须留在原地（不是「先收起表单再预演」）
      await expect(dlg, '预演失败时编辑表单不应收起').toBeVisible()
      // 原因写在表单里，用的是服务端原话（前缀「预演失败：」由 useDangerConfirm 加）
      const err = tid(page, 'delivery-form-preview-error')
      await expect(err, '预演失败原因应显示在表单内').toBeVisible()
      await expect(err, '表单内原因应是服务端原话').toHaveText(
        '预演失败：只有待下发包裹可以编辑（当前状态 1，已下发的只能取消）'
      )
      // 用户填的内容一个都没被重置
      const qtyInput = dlg.locator('.el-table__body tr').nth(0).locator('input').first()
      expect(await qtyInput.inputValue(), '已填的数量不能被重置').toBe(String(targetQty))
      await expect(tid(page, 'delivery-form-warehouse'), '已选的发货仓不能被重置').toContainText(pickedWh)
      // 确认弹窗（DangerConfirmDialog）自己收起，屏幕上只剩出错的那张表单
      await expect(dangerDialog(page), '预演失败后确认弹窗应收起').toBeHidden({ timeout: 10_000 })
    })

    await test.step('⑤ 收尾：关掉表单（数据已是「已下发」，只能取消）', async () => {
      await tid(page, 'delivery-form-cancel').click()
      await expect(dlg).toBeHidden({ timeout: 10_000 })
    })
  })

  /**
   * 合并弹窗同样「预演失败不收起表单」（`delivery-merge-preview-error`）。
   *
   * 与上一条同源：A 管理员打开「合并包裹」选好了目标包，B 管理员同时把源包下发了 →
   * 预演被守卫拦下。合并弹窗必须原地不动、刚选好的目标包裹不能丢，原因写在弹窗里。
   */
  test('合并弹窗预演失败：不收起、原因写在弹窗里、已选的目标包裹不丢', async ({ page, request }) => {
    test.setTimeout(180_000)
    await adminLoginHardened(page)
    const { product } = await firstProduct(request)

    let ctx
    await test.step('① 造单 → 审核通过 → 2 个待下发包裹', async () => {
      ctx = await prepareTwoPendingPackages(page, request, product.name, `mg-${Date.now()}`)
    })

    const mergeDialog = tid(page, 'delivery-merge-dialog')
    let targetSn
    await test.step('② 打开合并弹窗并选好目标包裹（留着不提交）', async () => {
      const rows = ctx.table.locator('.el-table__body tr')
      const sourceRow = deliveryRowBySn(ctx.table, ctx.snA)
      const otherRow = ctx.table.locator('.el-table__body tr').filter({ hasNotText: ctx.snA })
      await expect(rows, '应有 2 个包裹').toHaveCount(2)
      targetSn = (await otherRow.first().locator('td').first().innerText()).trim()
      expect(targetSn, '目标包裹号不能与源包相同').not.toBe(ctx.snA)
      await clickDeliveryAction(page, sourceRow, '合并')
      await expect(mergeDialog, '合并弹窗应打开').toBeVisible()
      await pickMergeTarget(page, targetSn)
      await expect(tid(page, 'delivery-merge-target'), '合并目标应已选中').toContainText(targetSn)
    })

    await test.step('③ 并发方把源包下发（页面上已无入口，只能走接口并发）', async () => {
      const res = await request.post(`${API}/admin/deliveries/${ctx.pkgA.id}/dispatch`, {
        headers: { Authorization: `Bearer ${ctx.token}` }
      })
      expect(res.status(), '并发下发应成功').toBe(200)
      const delivered = (await listDeliveriesApi(request, ctx.token, ctx.orderId))
        .find((d) => d.id === ctx.pkgA.id)
      expect(delivered.status, '并发方下发后源包应为已下发(1)').toBe(1)
    })

    await test.step('④ 点确定 → 预演失败：弹窗还在、原因在弹窗里、目标包裹没被清空', async () => {
      const previewResp = page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().includes('/admin/deliveries/') && r.url().includes('dryRun'),
        { timeout: 20_000 }
      )
      await tid(page, 'delivery-merge-submit').click()
      const resp = await previewResp
      const body = await resp.json()

      expect(body.code, '预演失败的业务码').toBe(1)
      expect(body.message, '预演失败的服务端原话')
        .toBe('只有待下发包裹可以合并（当前状态 1，已下发的只能取消）')

      await expect(mergeDialog, '预演失败时合并弹窗不应收起').toBeVisible()
      const err = tid(page, 'delivery-merge-preview-error')
      await expect(err, '预演失败原因应显示在合并弹窗内').toBeVisible()
      await expect(err, '弹窗内原因应是服务端原话')
        .toHaveText('预演失败：只有待下发包裹可以合并（当前状态 1，已下发的只能取消）')
      await expect(tid(page, 'delivery-merge-target'), '已选的目标包裹不能被清空').toContainText(targetSn)
      await expect(dangerDialog(page), '预演失败后确认弹窗应收起').toBeHidden({ timeout: 10_000 })
    })

    await test.step('⑤ 收尾：关掉合并弹窗', async () => {
      await tid(page, 'delivery-merge-cancel').click()
      await expect(mergeDialog).toBeHidden({ timeout: 10_000 })
    })
  })

  /**
   * 并发改同一条发货单：409 → 冲突弹窗 → 点刷新 → 表单带**最新版本号**重开 → 保存成功。
   *
   * 版本号是这条链路的核心：页面提交的是「打开弹窗那一刻」的行级 version，
   * 期间被别人改过一次就该 409（fail-closed，后端一个字段都没写），
   * 刷新后必须拿到最新那一行（最新 version + 别人的改动），再保存才成功。
   */
  test('并发改同一发货单：409 → 冲突弹窗 → 刷新 → 表单带最新版本重开 → 保存成功', async ({ page, request }) => {
    test.setTimeout(180_000)
    await adminLoginHardened(page)
    const { product } = await firstProduct(request)

    let ctx
    await test.step('① 造单 → 审核通过 → 2 个待下发包裹', async () => {
      ctx = await prepareTwoPendingPackages(page, request, product.name, `cf-${Date.now()}`)
    })

    const dlg = deliveryDialog(page)
    const apiList = () => listDeliveriesApi(request, ctx.token, ctx.orderId)
    let v0, bumpedQty, bumpedVersion, finalQty

    await test.step('② 打开编辑弹窗，改量（页面此刻持有的 version = v0）', async () => {
      const { table, snA, pkgA } = ctx
      v0 = pkgA.version
      const currentQty = pkgA.items[0].quantity
      bumpedQty = currentQty === 1 ? 2 : 1        // 并发方将要改成这个数量
      finalQty = currentQty                        // 我们刷新后在最新数据上再改回原值（一次真实保存）
      await clickDeliveryAction(page, deliveryRowBySn(table, snA), '编辑')
      await expect(dlg, '编辑弹窗应打开').toBeVisible()
      // 先改成本页自己的一个数量，确保提交的 items 非空（旧版本号仍然来自页面行数据）
      await setDialogQty(page, 0, bumpedQty)
    })

    await test.step('③ 并发方用 v0 改同一条发货单 → 版本号 +1', async () => {
      const put = await request.put(`${API}/admin/deliveries/${ctx.pkgA.id}`, {
        headers: { Authorization: `Bearer ${ctx.token}` },
        data: { items: [{ deliveryItemId: ctx.pkgA.items[0].id, quantity: bumpedQty }], version: v0 }
      })
      expect(put.status(), '并发方改量应成功').toBe(200)
      const fresh = (await apiList()).find((d) => d.id === ctx.pkgA.id)
      expect(fresh.version, '并发方改完后版本号应比页面手里的 v0 大 1').toBe(v0 + 1)
      expect(fresh.items[0].quantity, '并发方改完后库里的数量').toBe(bumpedQty)
      bumpedVersion = fresh.version
    })

    await test.step('④ 页面提交旧 version → 409 → 冲突弹窗', async () => {
      const execResp = page.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes('/admin/deliveries/') && !r.url().includes('dryRun'),
        { timeout: 20_000 }
      )
      const execReq = page.waitForRequest(
        (r) => r.method() === 'PUT' && r.url().includes('/admin/deliveries/') && !r.url().includes('dryRun')
      )
      await tid(page, 'delivery-form-submit').click()
      await confirmDanger(page, { willDo: ctx.snA })       // 预演能过（预演不带 version），执行才撞 409

      const req = await execReq
      expect(req.postDataJSON().version, '页面提交的应是它手里的旧版本号').toBe(v0)

      const resp = await execResp
      expect(resp.status(), '版本冲突必须是真 HTTP 409').toBe(409)
      const body = await resp.json()
      expect(body.code).toBe(409)
      expect(body.message, '409 的服务端原话')
        .toBe('数据已被他人修改（可能其他人在编辑），请刷新后重试')

      // 冲突弹窗（不是一闪而过的 toast）
      const conflict = conflictDialog(page)
      await expect(conflict, '409 应弹冲突弹窗').toBeVisible({ timeout: 10_000 })
      await expect(tid(page, 'conflict-message')).toHaveText('数据已被他人修改（可能其他人在编辑）')
      await expect(tid(page, 'conflict-hint')).toContainText('你刚才填写的内容没有被保存')
      // 危险确认弹窗被冲突处理接管后收起（不会两个弹窗叠着）
      await expect(dangerDialog(page), '409 由冲突弹窗接管，确认弹窗应收起').toBeHidden({ timeout: 10_000 })
    })

    await test.step('⑤ 点「刷新」→ 表单用最新那一行重开（最新 version + 别人的改动）', async () => {
      await conflictRefresh(page).click()
      await expect(conflictDialog(page), '刷新后冲突弹窗应收起').toBeHidden({ timeout: 10_000 })
      await expect(page.locator('.el-message--success').first(), '刷新成功提示')
        .toContainText('已刷新为最新数据，请重新修改后再保存')

      await expect(dlg, '刷新后编辑表单应重新打开').toBeVisible({ timeout: 15_000 })
      const qtyInput = dlg.locator('.el-table__body tr').nth(0).locator('input').first()
      expect(await qtyInput.inputValue(), '表单里应是并发方改后的最新数量')
        .toBe(String(bumpedQty))
    })

    await test.step('⑥ 在最新数据上再改一次并保存 → 带的必须是最新版本号，保存成功', async () => {
      await setDialogQty(page, 0, finalQty)
      const reqP = page.waitForRequest(
        (r) => r.method() === 'PUT' && r.url().includes('/admin/deliveries/') && !r.url().includes('dryRun')
      )
      const respP = page.waitForResponse(
        (r) => r.request().method() === 'PUT' && r.url().includes('/admin/deliveries/') && !r.url().includes('dryRun'),
        { timeout: 20_000 }
      )
      await submitDeliveryDialog(page)
      expect((await reqP).postDataJSON().version, '重开后提交的必须是最新版本号').toBe(bumpedVersion)
      expect((await respP).status(), '带最新版本号保存应成功').toBe(200)
      await expectToastSuccess(page)

      // 服务端复核：数量是我们最后填的、版本号又 +1
      const fresh = (await apiList()).find((d) => d.id === ctx.pkgA.id)
      expect(fresh.items[0].quantity, '最终数量').toBe(finalQty)
      expect(fresh.version, '保存成功后版本号再 +1').toBe(bumpedVersion + 1)
    })
  })
})
