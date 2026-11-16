<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <span>销售单审核队列</span>
          <span class="header-tip">
            每单只显示<b>最新一轮</b>的判定结论 · 徽标颜色 + 文案 + data-result 三重冗余
          </span>
        </div>
        <el-space :size="8">
          <el-button :icon="Refresh" data-testid="audit-queue-refresh" @click="refreshAll">刷新</el-button>
          <el-button :icon="Tickets" data-testid="audit-queue-runs" @click="openRuns">执行记录</el-button>
          <el-button type="primary" :icon="VideoPlay" :loading="running" data-testid="audit-queue-run" @click="runNow">
            立即审核一轮
          </el-button>
        </el-space>
      </div>
    </template>

    <!-- 筛选器 = 单选控件：同一时刻只筛一个结局（+「全部」），标准组件就是 el-radio-group。
         ------------------------------------------------------------------
         为什么不用 el-tag 当选择器（上一版的做法）：el-tag 是"标签"，不是表单控件 ——
           · 语义不对（不是 label，是 filter）；
           · plain 态的本色字压白底只有 2.2~3.1:1（绿 2.24 / 黄 2.19 / 红 2.90 / 灰 3.08），12px 读着费劲。
         换成 el-radio-button 之后：键盘 / 读屏 / 互斥分组全部由组件给，一行 props 都不用补，
         未选中态是白底深灰字（实测 6.11:1，改前是彩字 2.19~3.08:1，这是净改善）。
         选中态是 EP 的主色实底白字（实测 2.78:1）—— 这是**全站 `el-button type="primary"` 同一个值**，
         属 EP 主色本身的全局对比度问题，不是这一排引入的；要修就统一改主色或加深档，不在这里单独打补丁。
         代价：整排不再五色 —— 但五色已经在表格的「判定」徽标上编码过一次，筛选器不必再编码一遍。
         「被拦」不再单独圈一个分组框：阻止、冻结仍相邻（顺序 = 全部/自动过审/留人工/阻止/冻结/不命中，
         与改造前一致），相邻本身就表达了"同属被拦"，右边那行小字再说明两者处理人不同。
           （在单选组里套一个带标题的容器，会把"一整条分段控件"打成两块，反而更乱。） -->
    <div class="chips">
      <el-radio-group v-model="chipValue" size="large" data-testid="audit-queue-chips">
        <el-radio-button value="" data-testid="audit-chip-all">全部 <b>{{ counts.all }}</b></el-radio-button>
        <el-radio-button
          v-for="chip in filterChips"
          :key="chip.result"
          :value="chip.result"
          :title="auditResultHint(chip.result)"
          :data-testid="`audit-chip-${chip.result}`"
          :data-result="chip.result"
        >{{ chip.label }} <b>{{ counts[chip.result] }}</b></el-radio-button>
      </el-radio-group>

      <el-text size="small" type="info" class="chip-note">
        阻止 = 拦截下单，需人工联系客户；冻结 = 已打标等客服处理
        <span v-if="counts.skipped">· 另有 {{ counts.skipped }} 笔未参与本轮</span>
      </el-text>
    </div>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      :row-key="rowKey"
      border
      empty-text="当前筛选下没有待处理的审核记录"
      :row-class-name="rowClass"
      data-testid="audit-queue-table"
    >
      <el-table-column label="单号" min-width="235">
        <template #default="{ row }">
          <!-- el-table 的 <tr> 无法在模板上挂属性（没有 row-attributes API），
               所以这里保留「单号格锚点」audit-queue-row-<单号>，
               整行的 audit-row-<单号> 由 stampRowTestids() 在渲染后盖到 <tr> 上（见脚本区注释） -->
          <el-link
            type="primary"
            :data-testid="`audit-queue-row-${row.orderSn}`"
            @click="openOrderDrawer(row)"
          >{{ row.orderSn || '—' }}</el-link>
          <div class="muted">ID {{ row.orderId ?? '—' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="店铺 / 渠道" min-width="150">
        <template #default="{ row }">
          <div>{{ row.shopName || '—' }}</div>
          <div class="muted">{{ row.channel || '—' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="应收" width="110" align="right">
        <template #default="{ row }">
          <el-text class="mono">{{ money(row.totalAmount) }}</el-text>
        </template>
      </el-table-column>

      <el-table-column label="商品种类数" width="105" align="right">
        <template #default="{ row }">
          <span>{{ row.itemKindCount ?? '—' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="毛利" width="110" align="right">
        <template #default="{ row }">
          <el-text class="mono" :type="isNegative(row.grossProfit) ? 'danger' : undefined">{{ money(row.grossProfit) }}</el-text>
        </template>
      </el-table-column>

      <!-- 判定徽标：颜色 + 中文文案 + data-result 三重冗余 -->
      <el-table-column label="判定" width="132">
        <template #default="{ row }">
          <!-- 单元格里是**两个独立事实**，所以上下两枚而不是挤成一枚：
                 · 上：判定徽标 = 引擎这一轮判了什么（result，历史结论，冻/解冻都不改写它）；
                 · 下：冻结标记 = 这单**现在**有没有 active 的冻结标（frozen，来自标表）。
               人工冻的单没有新决策行（result 仍是 MANUAL / NO_MATCH / BLOCKED…），
               以前只有上面那枚 → 队列里根本看不出它冻着；现在靠下面这枚。
               为什么不开新列：这张表 10 列已经 1647px 顶满（见 playwright.config.js 的 1960 画幅推导），
               加一列就要横向滚动，「原因」列会被挤出画面 —— 把标记叠进判定列既省宽又语义相邻。 -->
          <div class="verdict-cell">
            <el-tag
              :type="auditResultTag(row.result)"
              effect="dark"
              size="small"
              data-testid="audit-row-badge"
              :data-result="row.result"
            >{{ auditResultText(row.result) }}</el-tag>
            <!-- title = 冻结原因：扫一眼知道"冻着"，悬停 / 读屏知道"为什么" -->
            <el-tag
              v-if="row.frozen"
              type="warning"
              effect="plain"
              size="small"
              class="frozen-mark"
              :title="frozenTitle(row)"
              :data-testid="`audit-row-frozen-${row.orderSn}`"
              :data-frozen-reason="row.frozenReason || ''"
              :data-frozen-tag="row.frozenTagCode || ''"
            >冻结中</el-tag>
          </div>
        </template>
      </el-table-column>

      <!-- 原因：第一行说「命中了哪条」，第二行灰字是后端的人话原因（含具体数值差），
           第三行（可选）是「假如没被拦本来会怎样」—— 只有被拦/冻结的行才有 -->
      <el-table-column label="原因" min-width="320">
        <template #default="{ row }">
          <!-- 四层是必要的语义分层（结论 / 后端原话 / 本可怎样 / 缺货附注），
               但前两层的字色字号正好等于 el-text 的 size+type，不再自己写：
                 reason-main → <el-text>（默认 14px + 正文字色；原来 13px，上浮 1px）
                 reason-sub  → size=small(12px) + type=info(#909399)，与原来一字不差
               只有 would-be 的「斜体+左竖线」和 stock-warn 的 11px 是 EP 表达不了的，留在样式块里。 -->
          <div class="reason-cell">
            <el-text data-testid="audit-row-reason">{{ reasonHead(row) }}</el-text>
            <!-- .reason-sub 这个类名别改：e2e 的 support/audit-ui.js reasonSubOf() 用它定位这一行 -->
            <el-text size="small" type="info" class="reason-sub">{{ row.reason || '—' }}</el-text>
            <!-- QT-1004 的核心叙事：策略条件其实满足了，只是被前一条策略先拦下 —— 必须看得见 -->
            <el-text v-if="wouldBeText(row)" size="small" type="info" class="reason-would-be" data-testid="audit-row-would-be">
              {{ wouldBeText(row) }}
            </el-text>
            <!-- 但「本可自动过审」只按 MATCH+排除 算过，**没跑库存预检** —— 缺货时要当场说清 -->
            <el-text v-if="wouldBeText(row) && stockWarnText(row)" size="small" type="warning" class="reason-stock-warn" data-testid="audit-row-would-be-stock">
              {{ stockWarnText(row) }}
            </el-text>
            <!-- 冻结原因：**冻着的单**必须自己把「因为什么」说出来，不能只靠悬停。
                 人工冻的单 result 不是 FROZEN，上面几行一个都不会讲冻结，所以这条不能省。 -->
            <el-text
              v-if="row.frozen"
              size="small"
              type="warning"
              class="reason-frozen"
              :data-testid="`audit-row-frozen-reason-${row.orderSn}`"
            >{{ frozenReasonText(row) }}</el-text>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="第几轮" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.roundNo">第 {{ row.roundNo }} 轮</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="时间" width="165">
        <template #default="{ row }">
          <span class="datetime">{{ fmtTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <!-- 固定右列：判定依据 / 流转记录 / 冻结解冻 -->
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="audit-row-why" @click="openWhy(row)">
            判定依据
          </el-button>
          <el-button link type="primary" size="small" data-testid="audit-row-trail" @click="openOrderDrawer(row)">
            流转记录
          </el-button>
          <!-- 冻结 / 解冻：判据是 **frozen（标表事实）**，不是 result（引擎结论）。
               —— 2026-11-16 修的就是这里：原来写的是 row.result === 'FROZEN'，
               而人工冻结**不产生新决策行**，result 仍是 MANUAL/NO_MATCH/… ⇒ 人工冻的单
               永远显示「冻结」按钮，解冻只能拿 curl 去救。
               反向也一样成立：策略冻的单解冻后 result 还是 FROZEN（决策行不该被改写），
               但标已经放了 ⇒ 这里必须显示「冻结」（否则点下去后端会报「当前没有被冻结」）。 -->
          <el-button
            v-if="row.frozen"
            link
            type="warning"
            size="small"
            data-testid="audit-row-unfreeze"
            @click="unfreeze(row)"
          >解冻</el-button>
          <el-button
            v-else
            link
            type="danger"
            size="small"
            data-testid="audit-row-freeze"
            @click="freeze(row)"
          >冻结</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        data-testid="audit-queue-pager"
        @current-change="loadQueue"
        @size-change="onSizeChange"
      />
    </div>

    <!-- ============ 「判定依据」抽屉：逐策略逐条件 expected vs actual ============ -->
    <el-drawer
      v-model="whyVisible"
      :title="`判定依据 - ${whyRow?.orderSn || ''}`"
      size="880px"
      data-testid="audit-why-drawer"
    >
      <template v-if="whyRow">
        <div class="why-head">
          <el-tag
            :type="auditResultTag(whyRow.result)"
            effect="dark"
            size="small"
            data-testid="audit-why-badge"
            :data-result="whyRow.result"
          >{{ auditResultText(whyRow.result) }}</el-tag>
          <el-text size="small" type="info" class="why-hint" data-testid="audit-why-hint">{{ auditResultHint(whyRow.result) }}</el-text>
        </div>
        <!-- 与队列行同一句：被拦/冻结时才有的「本来会怎样」（放在判定徽标 / hint 行正下方） -->
        <el-text v-if="wouldBeText(whyRow)" size="small" type="info" class="why-would-be" data-testid="audit-why-would-be">
          {{ wouldBeText(whyRow) }}
        </el-text>
        <!-- 同一句的缺货附注（与队列行一字不差） -->
        <el-text v-if="wouldBeText(whyRow) && stockWarnText(whyRow)" size="small" type="warning" class="why-stock-warn" data-testid="audit-why-would-be-stock">
          {{ stockWarnText(whyRow) }}
        </el-text>
        <!-- 阻止命中即返回 → EXCEPTION 段没跑过；要说出「这单还挂着退款申请」只能靠后端给的 other_gates -->
        <el-text v-if="otherGatesText(whyRow)" size="small" type="danger" class="why-line" data-testid="audit-why-other-gates">
          {{ otherGatesText(whyRow) }}
        </el-text>
        <el-alert class="why-reason" type="info" :closable="false" :title="whyRow.reason || '后端未回传原因'" />

        <div class="why-meta">
          <span>应收 {{ money(whyRow.totalAmount) }}</span>
          <span>商品种类数 {{ whyRow.itemKindCount ?? '—' }}</span>
          <span>毛利 {{ money(whyRow.grossProfit) }}</span>
          <span>第 {{ whyRow.roundNo ?? '—' }} 轮</span>
          <el-tag size="small" :type="whySource === 'BACKEND' ? 'success' : 'info'" effect="plain" data-testid="audit-why-source">
            {{ whySource === 'BACKEND' ? '结论取自后端留痕' : '本地对照（队列未回传条件留痕）' }}
          </el-tag>
        </div>

        <el-empty v-if="!whyGroups.length" description="这类结论没有可对照的策略（例如未参与本轮）" />

        <div v-for="g in whyGroups" :key="`${g.policyId}`" class="why-group" :data-testid="`audit-why-policy-${g.policyId}`">
          <div class="why-group-head">
            <el-text tag="b" :data-testid="`audit-why-policy-name-${g.policyId ?? 'unknown'}`">「{{ g.policyName }}」</el-text>
            <el-tag size="small" effect="plain">优先级 {{ g.priority ?? '—' }}</el-tag>
            <el-tag size="small" effect="plain">第 {{ g.versionNo ?? '—' }} 版</el-tag>
            <el-tag v-if="g.allSatisfied" size="small" type="success" effect="plain">条件全部满足</el-tag>
            <el-tag v-else size="small" type="danger" effect="plain">未命中</el-tag>
          </div>

          <el-text v-if="g.reasonLines.length" class="why-backend-lines" tag="div" size="small">
            <el-text class="muted" tag="div">后端留痕：</el-text>
            <el-text v-for="(line, i) in g.reasonLines" :key="i" class="why-backend-line" tag="div">· {{ line }}</el-text>
          </el-text>

          <el-table
            :data="g.conditions"
            border
            size="small"
            :row-class-name="whyRowClass"
            :row-style="whyRowStyle"
            empty-text="这条策略没有可对照的条件"
          >
            <el-table-column label="序号" width="60" align="center">
              <template #default="{ row }">{{ row.seq }}</template>
            </el-table-column>
            <el-table-column label="字段" min-width="140">
              <template #default="{ row }">
                <div>{{ row.fieldLabel || row.fieldCode }}</div>
                <div class="muted mono">{{ row.fieldCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作符" width="90" align="center">
              <template #default="{ row }">{{ auditOpText(row.opCode) }}</template>
            </el-table-column>
            <el-table-column label="期望 expected" min-width="130">
              <template #default="{ row }"><span class="mono">{{ row.expected }}</span></template>
            </el-table-column>
            <el-table-column label="实际 actual" min-width="150">
              <template #default="{ row }"><span class="mono">{{ row.actual }}</span></template>
            </el-table-column>
            <el-table-column label="结论" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'satisfied'" size="small" type="success" effect="plain">满足</el-tag>
                <el-tag v-else-if="row.status === 'unsatisfied'" size="small" type="danger" effect="plain">不满足</el-tag>
                <!-- AND 短路 / 字段没回传：显式标「未求值」，不混进「不满足」 -->
                <el-tag v-else size="small" type="info" effect="plain" data-testid="audit-why-unevaluated">未求值</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="120" fixed="right" align="center">
              <template #default="{ row }">
                <!-- 指向标 = el-tag：tag 本身 inline-flex + white-space:nowrap（不折行）、自带边框与底色，
                     比裸文字更适合"箭头指向这一行"；不再需要一行 white-space: nowrap 的自定义样式。 -->
                <el-tag v-if="row.status === 'unsatisfied'" size="small" type="danger" effect="plain" data-testid="audit-why-mark">← 此项未满足</el-tag>
                <span v-else-if="row.status === 'unevaluated'" class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <!-- 全局排除：不分策略，判定时并入每条自动策略；留人工的单很多就是被它挡掉的 -->
      <el-alert
        v-if="whyTargets.length && globalExclusions.length"
        class="why-global"
        type="warning"
        title="全局排除"
        :closable="false"
        show-icon
        data-testid="audit-why-global"
      >
        <el-text class="muted" tag="div">以下条件对每条自动策略一并求值：任一条成立，那条策略就被排除（结论转人工）</el-text>
        <el-text v-for="g in globalExclusions" :key="`g-${g.seq}`" class="why-backend-line" tag="div">· 排除：{{ condText(g) }}</el-text>
      </el-alert>

      <template #footer>
        <div class="why-footer">
          <el-button data-testid="audit-why-goto-policy" type="primary" plain @click="gotoPolicy">去改这条策略</el-button>
          <el-button data-testid="audit-why-manual-pass" type="success" @click="manualPass">人工审核通过</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- ============ 单笔抽屉：审核轨迹 tab ============ -->
    <el-drawer
      v-model="orderVisible"
      :title="`订单审核详情 - ${orderRow?.orderSn || ''}`"
      size="920px"
      data-testid="audit-order-drawer"
    >
      <el-tabs v-model="orderTab" data-testid="audit-order-tabs">
        <el-tab-pane label="审核轨迹" name="trail">
          <AuditTrail :order-id="orderRow?.orderId" />
        </el-tab-pane>
        <el-tab-pane label="判定摘要" name="summary">
          <!-- label-width 是必须的：el-descriptions 按内容自适应列宽，而「原因」那一行很长，
               会把 label 列挤到只剩一个字宽（实测「冻结状态」被折成「冻结状 / 态」，
               甚至「单号」也变成竖排）—— 固定 92px 后六个标签都整行可读。 -->
          <el-descriptions v-if="orderRow" :column="2" :label-width="92" border size="small">
            <el-descriptions-item label="单号">{{ orderRow.orderSn }}</el-descriptions-item>
            <el-descriptions-item label="第几轮">第 {{ orderRow.roundNo ?? '—' }} 轮</el-descriptions-item>
            <el-descriptions-item label="判定">
              <el-tag :type="auditResultTag(orderRow.result)" effect="dark" size="small">
                {{ auditResultText(orderRow.result) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="命中策略">{{ orderRow.matchedPolicyName || '—' }}</el-descriptions-item>
            <!-- 标签用「冻结」而不是「冻结状态」：el-descriptions 的 label 列窄，
                 四个字会被折成「冻结状 / 态」两行，读起来像坏掉 -->
            <el-descriptions-item label="冻结">
              <el-tag
                :type="orderRow.frozen ? 'warning' : 'info'"
                effect="plain"
                size="small"
                data-testid="audit-order-frozen"
                :data-frozen="orderRow.frozen ? 'true' : 'false'"
              >{{ orderRow.frozen ? '冻结中' : '未冻结' }}</el-tag>
            </el-descriptions-item>
            <!-- 人工冻的单在抽屉里必须能看到原因（队列行上只有一行小字，这里给全文 + 标码）。
                 testid 挂在内容 span 上而不是 el-descriptions-item：后者渲染成 <td> 时属性透传
                 依赖 EP 内部实现，挂在内层才是确定的锚点。 -->
            <el-descriptions-item label="冻结原因">
              <span data-testid="audit-order-frozen-reason">
                <span v-if="orderRow.frozen">{{ orderRow.frozenReason || '（未填写原因）' }}</span>
                <span v-else class="muted">—</span>
              </span>
              <el-text v-if="orderRow.frozen && orderRow.frozenTagCode" size="small" type="info" class="frozen-tagcode">
                标 {{ orderRow.frozenTagCode }}
              </el-text>
            </el-descriptions-item>
            <el-descriptions-item label="原因" :span="2">{{ orderRow.reason || '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- ============ 执行记录 ============ -->
    <el-dialog v-model="runsVisible" title="自动审核执行记录" width="900px" data-testid="audit-runs-dialog">
      <el-table v-loading="runsLoading" :data="runs" border size="small" empty-text="还没有执行过">
        <el-table-column label="runId" width="90" prop="id" />
        <el-table-column label="触发" width="100">
          <template #default="{ row }">{{ auditTriggerText(row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="范围内" width="90" prop="total" align="right" />
        <el-table-column label="自动过审" width="100" align="right">
          <template #default="{ row }"><el-text type="success">{{ row.autoPass }}</el-text></template>
        </el-table-column>
        <el-table-column label="留人工" width="90" align="right">
          <template #default="{ row }"><el-text type="warning">{{ row.manual }}</el-text></template>
        </el-table-column>
        <el-table-column label="阻止" width="80" align="right">
          <template #default="{ row }"><el-text type="danger">{{ row.blocked }}</el-text></template>
        </el-table-column>
        <el-table-column label="冻结" width="80" align="right">
          <template #default="{ row }"><el-text tag="b">{{ row.frozen }}</el-text></template>
        </el-table-column>
        <el-table-column label="不命中" width="90" align="right">
          <template #default="{ row }"><el-text type="info">{{ row.noMatch }}</el-text></template>
        </el-table-column>
        <el-table-column label="未参与" width="90" align="right" prop="skipped" />
        <el-table-column label="时间" min-width="165">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="runsQuery.page"
          v-model:page-size="runsQuery.size"
          :page-sizes="[10, 20, 50]"
          :total="runsTotal"
          layout="total, prev, pager, next"
          @current-change="loadRuns"
        />
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Tickets, VideoPlay } from '@element-plus/icons-vue'
import AuditTrail from '../../components/AuditTrail.vue'
import {
  listAuditQueue, queuePage, listAuditPolicies, policyBundle,
  listAuditRuns, runPage, runAudit,
  freezeOrderAudit, unfreezeOrderAudit
} from '../../api/audit'
import { auditPassDryRun, auditPassOrder } from '../../api/delivery'
import { useDangerConfirm } from '../../composables/useDangerConfirm'
import { explainRow, policiesForResult, expectedText } from '../../composables/useAuditExplain'
import {
  AUDIT_RESULT_TEXT, auditResultText, auditResultTag, auditResultHint,
  auditOpText, auditTriggerText
} from '../../constants/status'

const router = useRouter()
const danger = useDangerConfirm()

/**
 * ElMessageBox 的确认 / 取消按钮补 data-testid。
 *
 * Element Plus 的 MessageBox **不支持**往按钮上挂属性（没有按钮插槽，按钮由组件内部模板渲染），
 * 官方能影响的只有整框的 customClass。所以：每次弹框给一个专属 customClass → 挂载后按它找到
 * 这两个按钮，把 testid 盖上去。纯增量：找不到就静默放弃，e2e 仍可按文案点（不会更糟）。
 */
const stampMessageBoxButtons = (customClass, { confirm, cancel } = {}) => {
  let tries = 0
  const tryStamp = () => {
    const box = document.querySelector(`.el-message-box.${customClass}`)
    if (box) {
      const btns = Array.from(box.querySelectorAll('.el-message-box__btns .el-button'))
      // 确认键是 primary 那个；取不到就退化为「最后一个」（EP 的按钮顺序是 取消 → 确认）
      const confirmBtn = btns.find((b) => b.classList.contains('el-button--primary')) || btns[btns.length - 1]
      const cancelBtn = btns.find((b) => b !== confirmBtn)
      if (confirmBtn && confirm) confirmBtn.setAttribute('data-testid', confirm)
      if (cancelBtn && cancel) cancelBtn.setAttribute('data-testid', cancel)
      return
    }
    if (tries++ < 20) setTimeout(tryStamp, 16)
  }
  tryStamp()
}

/**
 * 筛选器的五个可选结局（「全部」= 空值，直接写在模板里）。
 *
 * ⚠️ 顺序是**沿用旧版**的：全部 → 自动过审 → 留人工 → 阻止 → 冻结 → 不命中。
 * 它按"判定链的严重度"排（先被拦的、后不命中的），从改造前到现在一直没变，不该顺手改；
 * 而且「阻止」「冻结」相邻本身就是原来那个虚线分组框想表达的意思（同属被拦、处理人不同），
 * 顺序已经够了，不必再套一层容器。
 */
const filterChips = [
  { result: 'AUTO_PASS', label: AUDIT_RESULT_TEXT.AUTO_PASS },
  { result: 'MANUAL', label: AUDIT_RESULT_TEXT.MANUAL },
  { result: 'BLOCKED', label: AUDIT_RESULT_TEXT.BLOCKED },
  { result: 'FROZEN', label: AUDIT_RESULT_TEXT.FROZEN },
  { result: 'NO_MATCH', label: AUDIT_RESULT_TEXT.NO_MATCH }
]

/**
 * 筛选值 ⇄ query.result 的桥：el-radio-group 的 v-model 用 '' 表示「全部」，
 * 而"不筛"在请求参数里必须是 null（'' 会被后端当成一个不存在的结局）。
 * set 里顺手做原来 setFilter 的两件事（回到第 1 页 + 重新拉队列），所以模板不必再绑 @change。
 */
const chipValue = computed({
  get: () => query.result || '',
  set: (v) => {
    query.result = v || null
    query.page = 1
    loadQueue()
  }
})

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const running = ref(false)
const query = reactive({ page: 1, size: 20, result: null })
const counts = reactive({ all: 0, AUTO_PASS: 0, MANUAL: 0, BLOCKED: 0, FROZEN: 0, NO_MATCH: 0, skipped: 0 })

const policies = ref([])
const globalExclusions = ref([])

/** 表格 :key：结局 + 订单 id —— 六支 id 各自自增的教训，单用 id 会撞 */
const rowKey = (row) => `${row.result}-${row.orderId}`

const money = (v) => {
  const n = Number(v)
  if (v === null || v === undefined || v === '' || !Number.isFinite(n)) return '—'
  return `¥ ${n.toFixed(2)}`
}
const isNegative = (v) => {
  const n = Number(v)
  return Number.isFinite(n) && n < 0
}
const fmtTime = (at) => (at ? String(at).replace('T', ' ').slice(0, 19) : '—')

/**
 * 冻结标记的 title（悬停 / 读屏）：标码 + 原因。
 *
 * 为什么这里**不**按 result 猜来源（「策略冻的」/「人工冻的」）：
 * 解冻后人工再冻一次，result 仍停在上一次的 FROZEN 上 —— 按 result 说来源会说错。
 * 判据只有标本身（tag_code + reason），所以 title 也只说这两件事。
 */
const frozenTitle = (row) => {
  const tag = row?.frozenTagCode || 'FROZEN'
  const why = row?.frozenReason ? row.frozenReason : '未填写原因'
  return `冻结中（标 ${tag}）：${why}`
}

/**
 * 原因列里的那一行「因为什么冻着」。
 * 人工冻的单 result 不是 FROZEN，reasonHead/reasonSub 讲的全是上一轮的判定，
 * 一个字都不会提到冻结 —— 所以这一行不能靠上面那几行代替。
 */
const frozenReasonText = (row) => (row?.frozenReason ? `冻结原因：${row.frozenReason}` : '冻结原因：（未填写）')

/** 原因栏第一行：说「命中了哪条 / 为什么没命中」，第二行灰字才是后端原话（含数值差） */
const reasonHead = (row) => {
  switch (row.result) {
    case 'AUTO_PASS': return row.matchedPolicyName ? `命中「${row.matchedPolicyName}」→ 自动过审` : '自动过审'
    case 'BLOCKED': return row.matchedPolicyName ? `命中阻止策略「${row.matchedPolicyName}」` : '被阻止策略拦截'
    case 'FROZEN': return row.matchedPolicyName ? `命中异常策略「${row.matchedPolicyName}」→ 已冻结` : '被异常策略命中'
    case 'MANUAL': return row.matchedPolicyName ? `「${row.matchedPolicyName}」条件满足但被排除` : '需要人工确认'
    case 'NO_MATCH': return '没有任何自动策略命中'
    case 'SKIPPED': return '本轮未参与判定'
    default: return row.result || '未知结论'
  }
}

/**
 * 「假如没被拦，本来会命中哪条自动策略」那一行。
 *
 * 后端判定链顺序固定：[3] BLOCK / [4] EXCEPTION 命中后**仍继续跑 [5] AUTO，只看不执行**，
 * 把「本来会命中的那条自动策略」回填成 would_be_auto_policy_name。
 * 也就是说：被阻止 ≠ 策略不满足，而是「满足但排在后面」—— 这句话是 QT-1004 的全部叙事，
 * 所以只在 BLOCKED / FROZEN 且后端真的回了策略名时才渲染（其它结局渲染出来就是谎话）。
 */
const WOULD_BE_RESULTS = ['BLOCKED', 'FROZEN']
const wouldBeText = (row) => {
  const name = row?.wouldBeAutoPolicyName
  if (!name) return ''
  if (!WOULD_BE_RESULTS.includes(String(row.result || '').toUpperCase())) return ''
  return `本可自动过审：命中「${name}」（被上面那条策略先拦下）`
}

/**
 * 上面那句的**附注**：would_be 只按 MATCH 条件 + 排除算，**不跑库存预检**（见 AuditEngine#wouldBe）。
 * 所以「本可自动过审」≠「解冻 / 放开后一定过审」：桶里不够时，STRICT 档会在 [7a] 把结论降级成
 * MANUAL + STOCK_SHORT（文案：转人工：库存不足 —— 可用 X < 需求 Y）。
 *
 * 数据来源：队列行回传的 parsed `evidence.stock = { bucket_sum, demand, guard, short, ... }`
 * （AuditEngine#evidence 对 BLOCKED / FROZEN 也照样写这一段）。short !== true 就一个字都不说 ——
 * 没有依据就不许加戏。档位 LENIENT 时缺货**不拦**，也就不能写「转人工」。
 */
const stockWarnText = (row) => {
  const stock = row?.evidence?.stock
  if (!stock || stock.short !== true) return ''
  // 只对 BLOCKED / FROZEN（有 would_be 那两句的）有意义；别的结局不加这句
  if (!WOULD_BE_RESULTS.includes(String(row.result || '').toUpperCase())) return ''
  const avail = stock.bucket_sum
  const need = stock.demand
  if (avail === null || avail === undefined || need === null || need === undefined) return ''
  const nums = `本单可用 ${avail} < 需求 ${need}`
  const guard = String(stock.guard || 'STRICT').toUpperCase()
  if (guard === 'LENIENT') {
    return `（注意：${nums}，但库存档是 LENIENT —— 缺货不拦，仍会自动过审，扣减时可能失败）`
  }
  return String(row.result || '').toUpperCase() === 'FROZEN'
    ? `（注意：${nums}，解冻后仍会因缺货转人工）`
    : `（注意：${nums}，即使不被拦也会因缺货转人工）`
}

/**
 * 「阻止命中即返回」的副作用：引擎在 [3] BLOCK 就 return 了，**[4] EXCEPTION 段根本没跑**，
 * 所以页面上的 BLOCKED 结论不会告诉你「这单其实还挂着一笔退款申请」。
 *
 * 这件事**只能由后端说**，前端不许猜（猜错就是让运营以为单子干净/脏）。
 * 期望契约（后端当前 **还没有**，见交付报告③）：队列行 evidence 里加
 *   "other_gates": [ { "type": "EXCEPTION", "policy_name": "退款申请冻结", "text": "有退款申请 = 是" } ]
 * 数组非空才渲染；空 / 缺失 = 这一行不出现（宁可不说，也不编）。
 */
const otherGatesText = (row) => {
  if (String(row?.result || '').toUpperCase() !== 'BLOCKED') return ''
  const gates = row?.evidence?.other_gates
  if (!Array.isArray(gates) || !gates.length) return ''
  const texts = gates
    .map((g) => g?.text ?? (Array.isArray(g?.reasons) ? g.reasons.join('；') : ''))
    .filter(Boolean)
  // 后端只给策略名、没给人话条件时，退化成「策略「X」命中」——仍然是有据可依的，不是编的
  const detail = texts.length
    ? texts.join('；')
    : gates.map((g) => g?.policyName ?? g?.policy_name).filter(Boolean).map((n) => `策略「${n}」命中`).join('；')
  if (!detail) return ''
  return `本单同时触发了异常策略（${detail}），已按阻止优先处理`
}

/** 一条条件的可读文案（全局排除块用；EXISTS/NOT_EXISTS 不显示值） */
const condText = (c) => {
  const label = c.fieldLabel || c.fieldCode
  const op = String(c.opCode || '').toUpperCase()
  if (op === 'EXISTS' || op === 'NOT_EXISTS') return `${label} ${auditOpText(op)}`
  return `${label} ${auditOpText(op)} ${expectedText(c)}`
}

/**
 * 行 class：跳过本轮的行整体降透明度；同时带一个 `audit-row-<单号>` 的类名，
 * 作为「行锚点」的**声明式兜底**（tr 挂不了属性时，e2e 也可以 `tr.audit-row-QT-1004` 定位）。
 */
const rowClass = ({ row }) => (row.orderSn ? `audit-row-${row.orderSn}` : '')

// ---------------------------------------------------------------- 行锚点

const tableRef = ref(null)

/**
 * 给每行 <tr> 盖 `data-testid="audit-row-<单号>"`。
 *
 * 为什么要在渲染后动 DOM：el-table 2.x **没有** row-attributes 之类的 API，<tr> 由组件内部
 * render 出来（table-body/render-helper 里写死），模板只够得着单元格内容。行锚点过去只能挂在
 * 单号格里，于是 e2e 想断言「这一行里有什么」都得先 `tr.filter({has: 单号锚点})` 绕一圈。
 * 这里在数据落到 DOM 之后按行序把 testid 盖到 tr 上，e2e 直接 `getByTestId('audit-row-QT-1004')`
 * 就是整行，行内断言不必再碰结构选择器。
 *
 * 与 rows 一一对应是安全的：本表无 tree / 无虚拟滚动 / 固定列是 sticky（不是另render一张表），
 * 所以 tbody 里一行一个 tr，顺序 == rows 顺序。
 */
const stampRowTestids = async () => {
  await nextTick()
  const root = tableRef.value?.$el
  if (!root) return
  const trs = root.querySelectorAll('.el-table__body tbody > tr')
  trs.forEach((tr, i) => {
    const sn = rows.value[i]?.orderSn
    if (sn) tr.setAttribute('data-testid', `audit-row-${sn}`)
    else tr.removeAttribute('data-testid')
  })
}

// 保险：loading 盖层撤掉后表体若重渲染，属性会跟着 tr 一起没 → 再盖一遍（幂等）
watch(loading, (v) => {
  if (!v) stampRowTestids()
})

const loadQueue = async () => {
  loading.value = true
  try {
    const page = queuePage(
      await listAuditQueue({ result: query.result, page: query.page, size: query.size })
    )
    rows.value = page.rows
    total.value = page.total
    // 行锚点依赖「数据已经渲染成 tr」这一步，所以放在赋值之后
    await stampRowTestids()
  } catch (e) {
    rows.value = []
    total.value = 0
    ElMessage.error('加载审核队列失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

/**
 * chips 上的计数：队列接口只回**当前筛选**的 total，所以按五种结果各发一个 size=1 的请求读 total。
 * （后端若加一个 /order-audit/queue/counts 汇总接口，这里换成一次请求即可，页面其它逻辑不动）
 */
const loadCounts = async () => {
  const results = ['AUTO_PASS', 'MANUAL', 'BLOCKED', 'FROZEN', 'NO_MATCH']
  try {
    const [all, ...rest] = await Promise.all([
      listAuditQueue({ page: 1, size: 1 }).then((p) => queuePage(p).total),
      ...results.map((r) => listAuditQueue({ result: r, page: 1, size: 1 }).then((p) => queuePage(p).total))
    ])
    counts.all = all
    results.forEach((r, i) => {
      counts[r] = rest[i]
    })
    counts.skipped = Math.max(0, all - results.reduce((s, r) => s + counts[r], 0))
  } catch (e) {
    // 计数失败不打断主流程：chips 显示上一轮的数字，表格照常可用
    ElMessage.warning('统计各类数量失败：' + (e?.message || e))
  }
}

const loadPolicies = async () => {
  try {
    const bundle = policyBundle(await listAuditPolicies())
    policies.value = bundle.policies
    // 全局排除：判 AUTO 时会被并进每条策略，所以抽屉里要单独列出来（否则解释不完整）
    globalExclusions.value = bundle.globalExclusions
  } catch (e) {
    ElMessage.error('加载审核策略失败：' + (e?.message || e))
  }
}

const onSizeChange = () => {
  query.page = 1
  loadQueue()
}

const refreshAll = () => {
  loadQueue()
  loadCounts()
}

const runNow = async () => {
  const box = ElMessageBox.confirm(
    '将立即对待审核（状态 7）的订单跑一轮自动审核：命中的会推进状态、拦截的会留痕。是否继续？',
    '立即审核一轮',
    { type: 'warning', confirmButtonText: '开始执行', cancelButtonText: '取消', customClass: 'audit-confirm-run' }
  )
  stampMessageBoxButtons('audit-confirm-run', { confirm: 'audit-confirm-run-ok', cancel: 'audit-confirm-run-cancel' })
  try {
    await box
  } catch {
    return
  }
  running.value = true
  try {
    const r = (await runAudit()) || {}
    ElMessage.success(
      `第 ${r.runId ?? '—'} 轮完成：范围内 ${r.total ?? 0} 笔 —— 自动过审 ${r.autoPass ?? 0}、`
      + `留人工 ${r.manual ?? 0}、阻止 ${r.blocked ?? 0}、冻结 ${r.frozen ?? 0}、`
      + `不命中 ${r.noMatch ?? 0}、未参与 ${r.skipped ?? 0}`
    )
    refreshAll()
  } catch (e) {
    ElMessage.error('执行失败：' + (e?.message || e))
  } finally {
    running.value = false
  }
}

// ---------------------------------------------------------------- 判定依据

const whyVisible = ref(false)
const whyRow = ref(null)

const openWhy = (row) => {
  whyRow.value = row
  whyVisible.value = true
}

/** 抽屉里要对照的策略：按结局选类别（阻止看 BLOCK，冻结看 EXCEPTION，其余看 AUTO） */
const whyTargets = computed(() => policiesForResult(whyRow.value?.result, policies.value))

/**
 * 每个策略一块：逐条件 expected vs actual。
 * 数据来源两级（见 composables/useAuditExplain.js）：
 *   ① 后端回传了结构化留痕（队列行 missReasons / conditions）→ 直接用，标「结论取自后端留痕」；
 *   ② 没有 → 用队列行上确实有的字段本地对一遍，取不到的字段如实标「未求值」。
 */
const whyGroups = computed(() => {
  if (!whyRow.value) return []
  return whyTargets.value.map((p) => explainRow(whyRow.value, p))
})

const whySource = computed(() => {
  const groups = whyGroups.value
  if (!groups.length) return 'LOCAL'
  return groups.some((g) => g.source === 'BACKEND') ? 'BACKEND' : 'LOCAL'
})

/**
 * 「判定依据」条件表的行类名：只是**语义钩子**（e2e 用 `tr.why-row-miss` 断言、
 * 调试时也能一眼看出这行是什么状态），不承担任何样式 —— 颜色由下面的 whyRowStyle 给。
 */
const whyRowClass = ({ row }) =>
  row.status === 'unsatisfied' ? 'why-row-miss' : row.status === 'unevaluated' ? 'why-row-unevaluated' : ''

/**
 * 「判定依据」条件表的行底色：不满足 = 红底，未求值 = 灰底灰字。
 *
 * 用 el-table 的 `row-style`（公开 prop，直接给 <tr> 落内联样式），而不是「row-class-name +
 * `:deep(.why-row-miss)`」——后者撬的是 el-table 内部渲染出来的 <tr>，scoped 选不中，只能 :deep。
 */
const whyRowStyle = ({ row }) => row.status === 'unsatisfied'
  ? { background: 'var(--el-color-danger-light-9)' }
  : row.status === 'unevaluated'
    ? { background: 'var(--el-fill-color-lighter)', color: 'var(--el-text-color-secondary)' }
    : undefined

/** 去改这条策略：带上策略 id 与第一条「不满足」条件的 seq，策略页会高亮那一行 */
const gotoPolicy = () => {
  const groups = whyGroups.value
  const target = groups.find((g) => g.conditions.some((c) => c.status === 'unsatisfied')) || groups[0]
  if (!target) {
    ElMessage.warning('这类结论没有对应的策略可改')
    return
  }
  const miss = target.conditions.find((c) => c.status === 'unsatisfied')
  router.push({
    path: '/audit-policy',
    query: {
      type: whyRow.value?.result === 'BLOCKED' ? 'BLOCK' : whyRow.value?.result === 'FROZEN' ? 'EXCEPTION' : 'AUTO',
      focusPolicy: target.policyId,
      ...(miss ? { focusSeq: miss.seq } : {})
    }
  })
}

/** 人工审核通过：走「预演 → 确认 → 执行」（7 → 8），不是无声写库 */
const manualPass = async () => {
  const row = whyRow.value
  if (!row) return
  await danger.run({
    dryRun: () => auditPassDryRun(row.orderId),
    exec: () => auditPassOrder(row.orderId),
    title: `人工审核通过订单 ${row.orderSn}`,
    confirmText: '确认通过',
    successText: `订单 ${row.orderSn} 已人工审核通过`,
    onSuccess: () => {
      whyVisible.value = false
      refreshAll()
    }
  })
}

// ---------------------------------------------------------------- 冻结 / 解冻

const freeze = async (row) => {
  let reason = ''
  const box = ElMessageBox.prompt(
    '冻结只会打标，**不会**改订单状态；本轮之后这张单不再参与自动审核，需人工解冻。',
    `冻结订单 ${row.orderSn}`,
    {
      confirmButtonText: '确认冻结',
      cancelButtonText: '取消',
      customClass: 'audit-confirm-freeze',
      inputPlaceholder: '冻结原因（例如：疑似刷单，等风控确认）',
      inputValidator: (v) => (v && v.trim() ? true : '请填写冻结原因')
    }
  )
  stampMessageBoxButtons('audit-confirm-freeze', { confirm: 'audit-confirm-freeze-ok', cancel: 'audit-confirm-freeze-cancel' })
  try {
    const res = await box
    reason = res.value
  } catch {
    return
  }
  try {
    await freezeOrderAudit(row.orderId, { reason })
    ElMessage.success('已冻结，下一轮不再自动审核')
    refreshAll()
  } catch (e) {
    ElMessage.error('冻结失败：' + (e?.message || e))
  }
}

const unfreeze = async (row) => {
  const box = ElMessageBox.confirm(
    '解冻会解除这张单上所有冻结类标；下一轮 run 才会重新判定。',
    `解冻订单 ${row.orderSn}`,
    { type: 'warning', confirmButtonText: '确认解冻', cancelButtonText: '取消', customClass: 'audit-confirm-unfreeze' }
  )
  stampMessageBoxButtons('audit-confirm-unfreeze', { confirm: 'audit-confirm-unfreeze-ok', cancel: 'audit-confirm-unfreeze-cancel' })
  try {
    await box
  } catch {
    return
  }
  try {
    const r = (await unfreezeOrderAudit(row.orderId)) || {}
    ElMessage.success(`已解冻（解除 ${r.released ?? 0} 个标）`)
    refreshAll()
  } catch (e) {
    ElMessage.error('解冻失败：' + (e?.message || e))
  }
}

// ---------------------------------------------------------------- 单笔抽屉

const orderVisible = ref(false)
const orderRow = ref(null)
const orderTab = ref('trail')

const openOrderDrawer = (row) => {
  orderRow.value = row
  orderTab.value = 'trail'
  orderVisible.value = true
}

// ---------------------------------------------------------------- 执行记录

const runsVisible = ref(false)
const runsLoading = ref(false)
const runs = ref([])
const runsTotal = ref(0)
const runsQuery = reactive({ page: 1, size: 10 })

const loadRuns = async () => {
  runsLoading.value = true
  try {
    const page = runPage(await listAuditRuns({ page: runsQuery.page, size: runsQuery.size }))
    runs.value = page.rows
    runsTotal.value = page.total
  } catch (e) {
    runs.value = []
    ElMessage.error('加载执行记录失败：' + (e?.message || e))
  } finally {
    runsLoading.value = false
  }
}

const openRuns = () => {
  runsVisible.value = true
  runsQuery.page = 1
  loadRuns()
}

onMounted(() => {
  loadQueue()
  loadCounts()
  loadPolicies()
})
</script>

<style scoped>
/* 与全局 .header-tip 只差一个左边距：字号/字色已在 main.css */
.card-header .header-tip { margin-left: 10px; }

/* ---- 筛选器：外观全部由 el-radio-group / el-radio-button 给，这里只有布局 ----
 * 分段控件自带：选中态 primary 实底白字、相邻按钮合并边框、圆角、间距、键盘焦点环。
 * gap 是"控件与小字说明之间"的间距（组件内部按钮之间不该有 gap，否则合并边框会断）。 */
.chips { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }

/* 「被拦」不再单独圈框：阻止 / 冻结 挨着排在末尾，由右边这句小字说明它们同属被拦、处理人不同 */
.chip-note { margin-left: 2px; }

/* 原因列只剩 4 条：字号/字色都已交给 el-text，这里只管「层级标记」和间距。
   word-break 可继承，所以一条就给整列（只对长单号/长 SKU 有意义，CJK 本来能断行）。 */
.reason-cell { display: flex; flex-direction: column; gap: 2px; word-break: break-all; }
/* el-text 自带 align-self:center（EP 的 text.scss:12），在纵向 flex 里会把文字**水平居中**。
   换 el-text 之后这一列的多行原因全歪到中间，读起来是散的 —— 必须拨回 stretch。
   这是布局声明（不是外观），所以不算破例；用 `> *` 直选子元素，不碰 :deep。 */
.reason-cell > * { align-self: stretch; }
/* 「本可自动过审：命中…（被上面那条策略先拦下）」这句自己带着主谓宾，与上一行「后端原话」
   靠 type 区分（info / warning）就够了，不再靠斜体+竖线喊"这不是结论" —— 那是审美，不是信息。 */
.reason-stock-warn { padding-left: 8px; }
/* 冻结原因那一行：与上面几行缩进对齐（外观全交给 el-text 的 size/type=warning） */
.reason-frozen { padding-left: 8px; }

/* 判定列：判定徽标 + 冻结标记上下叠，纵向排列、左对齐（不加宽就不挤掉「原因」列） */
.verdict-cell { display: flex; flex-direction: column; gap: 2px; align-items: flex-start; }
.frozen-tagcode { margin-left: 6px; }

/* 全局排除块 = el-alert（warning）：浅黄底、警示图标、标题层级全由组件给。
   原来是一张 el-card 自己染色 + 虚线边框，虚线和淡黄都是"这块不一样"的语气，不是信息：
   标题「全局排除」+ 正文已经把话说清了。 */
.why-global { margin-bottom: 18px; }
.why-global .why-backend-line { line-height: 20px; }

/* ---- 「判定依据」抽屉 ---- */
.why-head { display: flex; align-items: center; gap: 10px; }
/* 抽屉里与队列行同款的「本可怎样」：只有块级与间距，外观全是 el-text 的 size/type */
.why-would-be {
  display: block; margin-top: 6px;
}
/* 抽屉里的小字行（缺货附注 / 被后端校验拦住的原因 / 抽屉内的说明） */
.why-stock-warn, .why-line { display: block; margin-top: 4px; }
/* 后端原话 = el-alert(info)：浅底 + 图标，左边那条 3px 竖线删掉（信息量 0） */
.why-reason { margin-top: 8px; }
/* 字号回到 el-text 默认档（14px），原来是钉死的 13px —— 为追一个历史值让 el-text 在本页比别处小 1px 不值 */
.why-meta {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  margin: 12px 0;
}
.why-hint { margin-left: 2px; }
.why-group { margin-bottom: 20px; }
.why-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.why-backend-lines { margin-bottom: 6px; }
.why-backend-line { line-height: 20px; }
/* 「← 此项未满足」的"不折行"由 el-tag 自带（.el-tag 就是 inline-flex + white-space:nowrap），
   本文件不再为它保留任何外观声明。 */
.why-footer { display: flex; justify-content: flex-end; gap: 10px; }
/* 冻结计数：与其它数字同款 el-text tag="b"，不再单独染紫 ——
   紫是 FROZEN 徽标的色，而这一列是"冻结了几单"的数量，本来就不该跟徽标同色 */
</style>
