<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { message, notification } from 'ant-design-vue'

const API = '/api'
const activeTab = ref('models')
const asOf = ref(new Date().toISOString().slice(0, 10))
const recommendMinPrice = ref(null)
const recommendMaxPrice = ref(null)
const recommendations = ref([])
const marketContext = ref(null)
const poolMarketContext = ref(null)
const marketIndices = ref([])
const recommendationSnapshot = ref(null)
const recommendUniverseCount = ref(0)
const recommendHardPassedCount = ref(0)
const poolItems = ref([])
const poolCodes = ref([])
const selectedRowKeys = ref([])
const loading = ref(false)
const recommendationActionLoading = ref(false)
const poolLoading = ref(false)
const portfolioAnalysisLoading = ref(false)
const systemInitializationLoading = ref(false)
const modelRegeneratingKey = ref('')
const modelDataGeneratingKey = ref('')
const analysisStage = ref('准备分析数据')
const analysisElapsed = ref(0)
const analysisProgress = computed(() => Math.min(92, 8 + Math.round(analysisElapsed.value / 180 * 84)))
const analysisBusy = computed(() => loading.value || poolLoading.value || recommendationActionLoading.value || portfolioAnalysisLoading.value || systemInitializationLoading.value || Boolean(modelRegeneratingKey.value || modelDataGeneratingKey.value))
const manualVisible = ref(false)
const manualLoading = ref(false)
const eventVisible = ref(false)
const selectedEvent = ref(null)
const positionVisible = ref(false)
const positionLoading = ref(false)
const positionRecord = ref(null)
const positionRecordLoading = ref(false)
const selectedPosition = ref(null)
const historyVisible = ref(false)
const historyLoading = ref(false)
const selectedHistory = ref(null)
const historyTrades = ref([])
const scoreStructureVisible = ref(false)
const scoringModel = ref(null)
const modelStatuses = ref([])
const modelDetailVisible = ref(false)
const selectedModel = ref(null)
const positionForm = reactive({ buyPrice: null, quantity: null, openedAt: asOf.value })
const plannedVisible = ref(false)
const plannedLoading = ref(false)
const selectedPlanned = ref(null)
const plannedForm = reactive({ side: 'BUY', plannedPrice: null, quantity: null, tradeDate: asOf.value })
const maintenanceProgressStage = ref('准备处理')
const maintenanceProgressElapsed = ref(0)
const maintenanceProgress = computed(() => Math.min(92, 8 + Math.round(maintenanceProgressElapsed.value / 120 * 84)))
const accountVisible = ref(false)
const accountLoading = ref(false)
const accountAssets = ref(null)
const accountForm = reactive({ totalAssets: null })
const aiVisible = ref(false)
const aiLoading = ref(false)
const aiConfig = ref(null)
const aiForm = reactive({ provider: 'DeepSeek', model: 'deepseek-v4-pro', baseUrl: 'https://api.deepseek.com', apiKey: '', enabled: true })
const marketDataSourceVisible = ref(false)
const marketDataSourceLoading = ref(false)
const marketDataSources = ref([])
const systemInitializedAt = ref(null)
const portfolioAnalysisVisible = ref(false)
const portfolioAnalysis = ref(null)
const holdingOverviewVisible = ref(false)
const form = reactive({ code: '', name: '', industry: '' })
let analysisProgressTimer
let analysisProgressUsers = 0
let maintenanceProgressTimer
let maintenanceProgressUsers = 0
let portfolioAnalysisPollTimer
let portfolioAnalysisPollToken = 0

function startAnalysisProgress(stage = '准备分析数据') {
  analysisProgressUsers += 1
  if (analysisProgressUsers > 1) return
  analysisStage.value = stage
  analysisElapsed.value = 0
  const startedAt = Date.now()
  analysisProgressTimer = window.setInterval(() => {
    analysisElapsed.value = Math.floor((Date.now() - startedAt) / 1000)
    if (analysisElapsed.value < 4) analysisStage.value = '正在读取行情和策略配置'
    else if (analysisElapsed.value < 12) analysisStage.value = '正在整理历史技术数据'
    else if (analysisElapsed.value < 35) analysisStage.value = '正在读取公司资料缓存和新闻数据'
    else if (analysisElapsed.value < 90) analysisStage.value = 'AI 正在批量计算实时评分和价格建议'
    else analysisStage.value = '正在汇总分析结果，请保持页面打开'
  }, 500)
}

function finishAnalysisProgress() {
  analysisProgressUsers = Math.max(0, analysisProgressUsers - 1)
  if (analysisProgressUsers > 0) return
  if (analysisProgressTimer) window.clearInterval(analysisProgressTimer)
  analysisProgressTimer = undefined
}

function startMaintenanceProgress(stage = '准备处理') {
  maintenanceProgressUsers += 1
  if (maintenanceProgressUsers > 1) return
  maintenanceProgressStage.value = stage
  maintenanceProgressElapsed.value = 0
  const startedAt = Date.now()
  maintenanceProgressTimer = window.setInterval(() => {
    maintenanceProgressElapsed.value = Math.floor((Date.now() - startedAt) / 1000)
    if (maintenanceProgressElapsed.value < 4) maintenanceProgressStage.value = stage
    else if (maintenanceProgressElapsed.value < 15) maintenanceProgressStage.value = '正在读取行情和持仓数据'
    else if (maintenanceProgressElapsed.value < 45) maintenanceProgressStage.value = '正在计算分析结果'
    else maintenanceProgressStage.value = '正在汇总结果，请保持页面打开'
  }, 500)
}

function finishMaintenanceProgress() {
  maintenanceProgressUsers = Math.max(0, maintenanceProgressUsers - 1)
  if (maintenanceProgressUsers > 0) return
  if (maintenanceProgressTimer) window.clearInterval(maintenanceProgressTimer)
  maintenanceProgressTimer = undefined
}

const recommendationColumns = [
  { title: '股票/板块', key: 'stock', width: 180 },
  { title: '当前价', key: 'price', width: 125, align: 'right' },
  { title: '涨跌', key: 'change', width: 90, align: 'right' },
  { title: '100分 / 7项', key: 'score', width: 165, align: 'right' },
  { title: '推荐星级', key: 'rating', width: 110 },
  { title: '推荐原因', key: 'reason', width: 280 },
  { title: 'AI公司分析', key: 'companyAi', width: 320 },
  { title: '买入区间', key: 'buy', width: 150, align: 'right' },
  { title: '重大事件', key: 'event', width: 210 },
  { title: '系统建议', key: 'action', width: 110 }
]

const poolColumns = [
  { title: '股票/板块', key: 'stock', width: 180 },
  { title: '当前价', key: 'price', width: 125, align: 'right' },
  { title: '涨跌', key: 'change', width: 90, align: 'right' },
  { title: '持仓/计划', key: 'position', width: 220 },
  { title: '评分 / 7项', key: 'score', width: 165, align: 'right' },
  { title: '历史准确率', key: 'accuracy', width: 145, align: 'center' },
  { title: '操作建议', key: 'signal', width: 135 },
  { title: '当前波段', key: 'band', width: 120 },
  { title: '买入参考', key: 'buy', width: 160, align: 'right' },
  { title: '卖出参考', key: 'sell', width: 160, align: 'right' },
  { title: '风险', key: 'risk', width: 105 },
  { title: '重大事件', key: 'event', width: 200 },
  { title: '管理', key: 'manage', width: 80 }
]

const marketDataSourceColumns = [
  { title: '启用', key: 'enabled', width: 62 },
  { title: '名称', key: 'name', width: 150 },
  { title: '用途', key: 'purpose', width: 100 },
  { title: '适配器', key: 'adapter', width: 110 },
  { title: '优先级', key: 'priority', width: 90 },
  { title: '接口地址', key: 'endpoint', width: 360 },
  { title: '超时(秒)', key: 'timeoutSeconds', width: 100 },
  { title: '重试次数', key: 'retryCount', width: 100 },
  { title: 'User-Agent', key: 'userAgent', width: 220 },
  { title: 'Referer', key: 'referer', width: 220 }
]

const defaultScoringModel = {
  strategyId: 'value-quality-100', version: 1, confidence: 0,
  businessModelWeight: 15, industryProspectWeight: 15, competitiveAdvantageWeight: 15,
  financialQualityWeight: 20, growthWeight: 15, valuationWeight: 10, catalystWeight: 5, riskWeight: 5
}

const currentScoringModel = computed(() => scoringModel.value || defaultScoringModel)
const scoringModelCardName = computed(() => `价值质量 100 分模型 · V${currentScoringModel.value.version || 1}`)
const scoringModelCardScore = computed(() => {
  const confidence = Number(currentScoringModel.value.confidence)
  return Number.isFinite(confidence) ? Math.round(confidence * 100) : 0
})
const scoringModelCardSummary = computed(() => {
  const model = currentScoringModel.value
  return `当前生效权重：商业模式${model.businessModelWeight} / 行业前景${model.industryProspectWeight} / 竞争优势${model.competitiveAdvantageWeight} / 财务质量${model.financialQualityWeight} / 成长性${model.growthWeight} / 估值${model.valuationWeight} / 催化剂${model.catalystWeight} / 风险${model.riskWeight}；可信度${formatPercentValue(Number(model.confidence || 0) * 100)}。`
})

function modelDisplayName(model) {
  if (!model) return ''
  const status = modelStatusInfo(model)
  return `${model.name} · ${status?.version > 0 ? `V${status.version}` : '未入库'}`
}

function modelDisplayScore(model) {
  if (model?.key === 'stock-score' && !modelStatusInfo(model)?.persisted) return 0
  return model?.key === 'stock-score' ? scoringModelCardScore.value : Number(modelStatusInfo(model)?.score || 0)
}

function modelDisplaySummary(model) {
  return model?.key === 'stock-score' ? scoringModelCardSummary.value : model.summary
}

const selectedModelDisplay = computed(() => {
  if (!selectedModel.value || selectedModel.value.key !== 'stock-score') return selectedModel.value
  return {
    ...selectedModel.value,
    name: scoringModelCardName.value,
    score: scoringModelCardScore.value,
    summary: scoringModelCardSummary.value,
    method: `系统始终读取已保存的${scoringModelCardName.value}；当前权重和可信度来自评分模型快照。`
  }
})

const scoreStructure = computed(() => {
  const model = currentScoringModel.value
  return [
    { label: '商业模式', score: model.businessModelWeight, color: 'default', note: '好公司基础', description: '结合公司主营、收入来源、商业闭环和可持续性，资料来自公司资料、公告和AI结构化摘要。' },
    { label: '行业前景', score: model.industryProspectWeight, color: 'default', note: '好行业空间', description: '结合行业分类、行业景气、政策与新闻公告，判断行业未来需求和竞争格局。' },
    { label: '竞争优势', score: model.competitiveAdvantageWeight, color: 'default', note: '护城河', description: '结合公司业务、盈利能力、行业位置和AI资料，判断品牌、技术、渠道或成本优势。' },
    { label: '财务质量', score: model.financialQualityWeight, color: 'default', note: '经营质量', description: '使用披露日可用的ROE、负债率、净利润和现金流等财务数据，避免使用未来数据。' },
    { label: '成长性', score: model.growthWeight, color: 'default', note: '业绩增长', description: '结合营收增长、净利润增长、连续性和行业景气判断成长空间。' },
    { label: '估值', score: model.valuationWeight, color: 'default', note: '合理价格', description: '优先使用入库PE、PB、PEG、DCF等估值数据；估值数据缺失时明确显示未覆盖。' },
    { label: '催化剂', score: model.catalystWeight, color: 'default', note: '近期变量', description: '使用新闻、公告、分红、回购、订单、产品进展等多渠道数据和AI摘要。' },
    { label: '风险', score: model.riskWeight, color: 'default', note: '安全边际', description: '风险越低得分越高，综合财务风险、重大负面公告、波动和数据可信度。' }
  ]
})

// 按独立决策模块统计；底层 AI 供应商/模型不重复计数。
const systemModels = [
  {
    key: 'stock-score', name: '价值质量 100 分模型', type: '选股模型', color: 'blue', requiresAi: false,
    summary: '把商业模式、行业、竞争优势、财务、成长、估值、催化剂和风险汇总为 100 分。',
    inputs: '行情、公司资料、财务披露、新闻公告、策略权重', outputs: '综合评分、因子分解、覆盖率、买入区间',
    method: '8 个评分因子加权，风险因子按安全边际计分；数据未覆盖时明确降低覆盖率，不伪造分数。',
    formula: '综合评分 S = Σ(因子得分 ÷ 因子满分 × 因子权重) − 风险调整',
    steps: ['行情与资料', '8 项因子评分', '权重合成', '覆盖率与风险校正', '0–100 分'],
    action: '查看评分结构', actionType: 'score'
  },
  {
    key: 'company-profile', name: 'AI 公司画像模型', type: '基本面分析', color: 'purple', requiresAi: true,
    summary: '从公司资料、公告和新闻中提炼主营业务、行业前景、趋势和主要风险。',
    inputs: '公司资料、公告、新闻热点、财务摘要', outputs: '主营描述、未来趋势、风险摘要、AI 置信度',
    method: '使用当前 AI 接入配置批量生成结构化画像，并写入公司资料缓存供后续分析复用。',
    formula: '画像 = 结构化抽取(公司资料 + 公告 + 新闻) → 业务 / 前景 / 趋势 / 风险',
    steps: ['资料、公告、新闻', 'AI 结构化提炼', '事实交叉检查', '画像缓存', '基本面摘要'],
    action: '维护 AI 接入', actionType: 'ai'
  },
  {
    key: 'realtime-score', name: 'AI 实时评分模型', type: '盘中分析', color: 'cyan', requiresAi: true,
    summary: '结合当天行情、市场环境和公司画像，更新股票的实时因子评分。',
    inputs: '实时行情、市场指数、技术指标、公司画像、新闻情绪', outputs: '今日因子分数、最终分数、市场环境标签',
    method: '批量分析股票池和推荐候选，结果保存为快照；页面刷新优先读取已保存的分析结果。',
    formula: '实时评分 = f(今日行情, 技术指标, 市场环境, 公司画像)',
    steps: ['实时行情', '技术指标', '市场环境', 'AI 批量重算', '今日评分快照'],
    action: '进入推荐中心', actionType: 'recommendations'
  },
  {
    key: 'price-advice', name: 'AI 价格建议模型', type: '价格模型', color: 'gold', requiresAi: true,
    summary: '根据趋势、支撑阻力和风险约束给出买入区间、止盈和止损参考。',
    inputs: '历史 K 线、实时价格、技术指标、评分结果、公司风险', outputs: '买入区间、承接位、止盈位、硬止损、跟踪止损',
    method: '价格建议只提供参考，买入价仍受当前真实价格和系统交易纪律校验。',
    formula: '价格建议 = f(趋势, 支撑阻力, 波动率, 评分, 风险约束)',
    steps: ['趋势与波动', '支撑/阻力识别', '评分与风险约束', '生成价格带', '买入 / 止盈 / 止损'],
    action: '进入股票池', actionType: 'pool'
  },
  {
    key: 'success-rate', name: '交易成功率模型', type: '概率模型', color: 'orange', requiresAi: true,
    summary: '估算某次计划交易在当前条件下的成功概率，不等同于历史统计胜率。',
    inputs: 'AI 价格匹配度、技术匹配度、风险等级、历史预测和执行结果', outputs: '成功概率区间、置信度、概率解释',
    method: '每小时生成并保存股票级模型快照，计划分析只读取快照，不在点击计划时临时调用 AI。',
    formula: 'P = clamp(基础概率 + 匹配加分 − 风险扣分, 最小值, 最大值)',
    steps: ['基础概率', 'AI/技术匹配度', '风险扣分', '置信度校正', '成功概率区间'],
    action: '进入股票池', actionType: 'pool'
  },
  {
    key: 'plan-analysis', name: '计划分析模型', type: '交易计划', color: 'geekblue', requiresAi: true,
    summary: '把价格、数量、方向、仓位和风险规则合并，判断计划是否值得执行。',
    inputs: '计划价格和数量、当前价、持仓、成功率模型、止盈止损规则', outputs: '可以考虑/无法执行/成功率低/不建议、操作状态、建议、风险提醒',
    method: '计划仅分析不落库；只有用户确认今日交易后才会写入交易记录。',
    formula: '计划结论 = 可执行性分类 + 概率分类 + 风险分类 + 价格建议',
    steps: ['价格与数量', '可执行性校验', '概率与风险', '规则决策', '分级结论'],
    action: '进入股票池', actionType: 'pool'
  },
  {
    key: 'position-risk', name: '持仓与仓位风险模型', type: '风控模型', color: 'green', requiresAi: false,
    summary: '围绕成本、持仓比例、账户总资产和止盈止损管理已有仓位。',
    inputs: '账户资产、持仓成本和数量、实时价格、策略仓位上限', outputs: '仓位比例、盈亏、加仓额度、持仓状态',
    method: '按单股和行业仓位上限计算，优先执行硬止损和盈利回撤止损规则。',
    formula: '仓位比例 = 持仓市值 ÷ 总资产；可加仓额 = 上限市值 − 已持仓市值 − 计划市值',
    steps: ['账户总资产', '持仓市值', '单股/行业上限', '盈亏与止损', '仓位状态'],
    action: '进入股票池', actionType: 'pool'
  },
  {
    key: 'sell-decision', name: '卖出决策模型', type: '退出模型', color: 'red', requiresAi: false,
    summary: '识别趋势破坏、超买转弱、止损和止盈触发条件，形成卖出提示。',
    inputs: '实时行情、持仓成本、RSI、MACD、止盈止损线、重大事件', outputs: '卖出状态、卖出参考价、风险提示',
    method: '硬止损和盈利回撤止损优先级最高，提示用于辅助决策，不会自动下单。',
    formula: '卖出触发 = 硬止损 OR 趋势转弱 OR 止盈回撤 OR 重大风险',
    steps: ['实时价格', '趋势/指标变化', '止盈止损线', '优先级判断', '卖出提示'],
    action: '进入股票池', actionType: 'pool'
  },
  {
    key: 'portfolio-review', name: '组合复盘模型', type: '组合分析', color: 'purple', requiresAi: true,
    summary: '结合大盘、全部持仓和已确认交易，复盘决策成功点、失误点和下一步动作。',
    inputs: '市场环境、持仓盈亏、历史交易、预测准确率、执行准确率', outputs: '组合概况、成功点、失误点、原因、下一步建议',
    method: '以组合维度生成复盘摘要，并展示 AI 置信度和风险提醒。',
    formula: '组合复盘 = 市场环境 + 持仓盈亏 + 预测/执行结果 → 成功 / 失误 / 原因 / 下一步',
    steps: ['大盘环境', '持仓与交易', '预测/执行结果', 'AI 归因复盘', '行动建议'],
    action: '进入股票池', actionType: 'pool'
  }
]

const systemModelCount = systemModels.length

function modelStatusInfo(model) {
  return modelStatuses.value.find(status => status.modelKey === model?.key) || null
}

function modelStatus(model) {
  const status = modelStatusInfo(model)
  if (model.requiresAi && aiConfig.value?.enabled === false) return '已停用'
  if (!status) return '读取中'
  if (!status.databaseAvailable) return '数据库不可用'
  if (!status.persisted) return '未入库'
  return status.usageCount > 0 ? '已使用' : '已入库未使用'
}

function modelStatusColor(model) {
  const value = modelStatus(model)
  if (value === '已使用') return 'green'
  if (value === '数据库不可用' || value === '未入库') return 'red'
  if (value === '已入库未使用' || value === '已停用') return 'orange'
  return 'default'
}

function modelEvidence(model) {
  const status = modelStatusInfo(model)
  if (!status) return '正在读取数据库审计信息'
  if (!status.databaseAvailable) return '数据库不可用，未使用内存结果冒充已入库'
  const persisted = status.persisted ? `${status.persistedRecords} 条记录` : '未入库'
  return `数据库 ${persisted} · 使用 ${status.usageCount || 0} 次 · 使用股票 ${status.usedRecords || 0} 只`
}

function modelGeneratedAt(model) {
  const value = modelStatusInfo(model)?.generatedAt
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function openModelDetail(model) {
  selectedModel.value = model
  modelDetailVisible.value = true
}

function handleModelAction(model) {
  modelDetailVisible.value = false
  if (model?.actionType === 'score') return openScoreStructure()
  if (model?.actionType === 'ai') return (aiVisible.value = true)
  activeTab.value = model?.actionType === 'recommendations' ? 'recommendations' : 'pool'
}

async function refreshModelView(model, result) {
  if (model.key === 'stock-score') {
    await Promise.all([loadRecommendations(), loadScoringModel()])
  } else if (model.key === 'portfolio-review') {
    portfolioAnalysis.value = result.portfolioAnalysis || null
    portfolioAnalysisVisible.value = true
  } else if (model.key !== 'company-profile' || poolItems.value.length) {
    await loadPool()
  }
  await loadModelStatuses()
}

async function regenerateModel(model) {
  if (!model || modelRegeneratingKey.value || modelDataGeneratingKey.value) return
  modelRegeneratingKey.value = model.key
  startAnalysisProgress(`正在重新生成${model.name}模型`)
  try {
    const result = await request(`/managed-pool/models/${encodeURIComponent(model.key)}/regenerate?asOf=${asOf.value}`, { method: 'POST' })
    if (result?.error) throw new Error(result.error)
    await refreshModelView(model, result)
    const audit = modelStatusInfo(model)
    if (!audit?.persisted) message.warning(`${model.name}已执行生成，但数据库未确认入库，请检查数据库连接`)
    else message.success(`${model.name}模型已重新生成并入库：V${audit.version}，使用记录 ${audit.usageCount || 0} 次`)
  } catch (error) {
    message.error(`重新生成${model.name}模型失败：${error.message}`)
  } finally {
    modelRegeneratingKey.value = ''
    finishAnalysisProgress()
  }
}

async function generateModelData(model) {
  if (!model || modelRegeneratingKey.value || modelDataGeneratingKey.value) return
  modelDataGeneratingKey.value = model.key
  startAnalysisProgress(`正在使用${model.name}生成数据`)
  try {
    const result = await request(`/managed-pool/models/${encodeURIComponent(model.key)}/data/refresh?asOf=${asOf.value}`, { method: 'POST' })
    if (result?.error) throw new Error(result.error)
    await refreshModelView(model, result)
    const audit = modelStatusInfo(model)
    if (!audit?.persisted) message.warning(`${model.name}数据处理已执行，但数据库未确认入库，请检查数据库连接`)
    else message.success(`${model.name}数据已生成并入库：V${audit.version}，使用记录 ${audit.usageCount || 0} 次`)
  } catch (error) {
    message.error(`生成${model.name}数据失败：${error.message}`)
  } finally {
    modelDataGeneratingKey.value = ''
    finishAnalysisProgress()
  }
}

const selectedStocks = computed(() => recommendations.value.filter(item => selectedRowKeys.value.includes(item.stock.code)))
const candidateCount = computed(() => recommendations.value.filter(item => item.action === '候选').length)
const majorEventCount = computed(() => recommendations.value.filter(item => item.stock.majorEventType).length)
const highRiskCount = computed(() => poolItems.value.filter(item => item.tradePlan.riskLevel === 'HIGH').length)
const avgScore = computed(() => {
  if (!poolItems.value.length) return 0
  return Math.round(poolItems.value.reduce((sum, item) => sum + item.analysis.scores.finalScore, 0) / poolItems.value.length)
})
const holdingOverview = computed(() => poolItems.value
  .filter(item => item.position?.hasPosition)
  .map(item => ({
    key: item.code,
    code: item.code,
    name: item.analysis?.stock?.name || item.code,
    stock: item.analysis?.stock,
    position: item.position,
    tradePlan: item.tradePlan
  })))
const holdingOverviewSummary = computed(() => holdingOverview.value.reduce((summary, item) => {
  const position = item.position || {}
  const cost = Number(position.buyPrice || 0) * Number(position.quantity || 0)
  summary.cost += cost
  summary.marketValue += Number(position.marketValue || 0)
  summary.pnl += Number(position.pnlAmount || 0)
  summary.quantity += Number(position.quantity || 0)
  return summary
}, { cost: 0, marketValue: 0, pnl: 0, quantity: 0 }))
const holdingOverviewPnlPercent = computed(() => holdingOverviewSummary.value.cost > 0
  ? holdingOverviewSummary.value.pnl / holdingOverviewSummary.value.cost * 100 : null)

function openHoldingOverview() {
  holdingOverviewVisible.value = true
}

function scoreFactors(scores) {
  const model = currentScoringModel.value
  return [
    { label: '商业模式', value: scores?.businessModel ?? 0, max: model.businessModelWeight },
    { label: '行业前景', value: scores?.industryProspect ?? 0, max: model.industryProspectWeight },
    { label: '竞争优势', value: scores?.competitiveAdvantage ?? 0, max: model.competitiveAdvantageWeight },
    { label: '财务质量', value: scores?.financialQuality ?? 0, max: model.financialQualityWeight },
    { label: '成长性', value: scores?.growth ?? 0, max: model.growthWeight },
    { label: '估值', value: scores?.valuation ?? 0, max: model.valuationWeight },
    { label: '催化剂', value: scores?.catalyst ?? 0, max: model.catalystWeight },
    { label: '风险', value: scores?.risk ?? 0, max: model.riskWeight }
  ]
}

function factorClass(label) {
  return {
    商业模式: 'factor-trend',
    行业前景: 'factor-momentum',
    竞争优势: 'factor-volume',
    财务质量: 'factor-fundamental',
    成长性: 'factor-capital',
    估值: 'factor-quality',
    催化剂: 'factor-ai',
    风险: 'factor-risk'
  }[label] || ''
}

function recommendationReasonClass(reason) {
  const text = String(reason || '')
  if (/(风险|止损|利空|不可买|不可卖|数据不足)/.test(text)) return 'reason-risk'
  if (/(数据未接入|未评分|不可用|不参与评分|暂无)/.test(text)) return 'reason-data'
  if (/AI/.test(text)) return 'reason-ai'
  if (/(通过|趋势|动量|量价|基本面|资金面|质量|候选|利好)/.test(text)) return 'reason-positive'
  return ''
}

function companyTrendColor(trend) {
  return trend === '上行倾向' ? 'blue' : trend === '下行风险' ? 'red' : 'default'
}

const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: keys => { selectedRowKeys.value = keys }
}))

async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, {
    headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
    ...options
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const error = new Error(body.message || `请求失败（${response.status}）`)
    error.status = response.status
    throw error
  }
  return response.json()
}

async function loadRecommendations({ silent = false } = {}) {
  loading.value = true
  startAnalysisProgress('正在读取推荐结果')
  try {
    await loadConfig()
    const params = new URLSearchParams({ asOf: asOf.value })
    if (recommendMinPrice.value !== null && recommendMinPrice.value !== undefined && recommendMinPrice.value !== '') params.set('minPrice', recommendMinPrice.value)
    if (recommendMaxPrice.value !== null && recommendMaxPrice.value !== undefined && recommendMaxPrice.value !== '') params.set('maxPrice', recommendMaxPrice.value)
    const data = await request(`/recommendations?${params.toString()}`)
    if (!Number.isFinite(Number(data.universeCount))) throw new Error('推荐行情返回不完整')
    applyRecommendationData(data)
    return true
  } catch (error) {
    if (!silent) message.error(`${error.message}，将在5分钟后继续重试`)
    return false
  } finally {
    loading.value = false
    finishAnalysisProgress()
  }
}

function applyRecommendationData(data) {
  recommendations.value = data.items || []
  marketContext.value = data.marketContext || null
  marketIndices.value = data.marketIndices || []
  recommendationSnapshot.value = data.snapshot || null
  recommendUniverseCount.value = data.universeCount || 0
  recommendHardPassedCount.value = data.hardPassedCount || 0
}

async function loadConfig() {
  const config = await request('/strategy-config')
  recommendMinPrice.value = config.minPrice == null ? null : Number(config.minPrice)
  recommendMaxPrice.value = config.maxPrice == null ? null : Number(config.maxPrice)
}

async function loadScoringModel() {
  try { scoringModel.value = await request('/scoring-model') } catch (error) { message.warning(`读取评分模型失败：${error.message}`) }
}

async function loadModelStatuses() {
  try {
    modelStatuses.value = await request(`/managed-pool/models/status?asOf=${asOf.value}`)
  } catch (error) {
    modelStatuses.value = []
    message.warning(`读取模型数据库审计失败：${error.message}`)
  }
}

async function openScoreStructure() {
  scoreStructureVisible.value = true
  await loadScoringModel()
}

async function loadAccountAssets() {
  try {
    const data = await request('/account/assets')
    accountAssets.value = data
    accountForm.totalAssets = data.totalAssets == null ? null : Number(data.totalAssets)
  } catch (error) { message.error(`读取账户总资产失败：${error.message}`) }
}

async function loadAiConfig() {
  try {
    const data = await request('/ai/config')
    aiConfig.value = data
    Object.assign(aiForm, {
      provider: data.provider || 'DeepSeek',
      model: data.model || 'deepseek-v4-pro',
      baseUrl: data.baseUrl || 'https://api.deepseek.com',
      apiKey: '',
      enabled: data.enabled !== false
    })
  } catch (error) { message.error(`读取 AI 接入配置失败：${error.message}`) }
}

async function loadMarketDataSources() {
  try {
    marketDataSources.value = await request('/market-data/sources')
  } catch (error) { message.error(`读取行情源配置失败：${error.message}`) }
}

async function saveMarketDataSources() {
  marketDataSourceLoading.value = true
  try {
    marketDataSources.value = await request('/market-data/sources', {
      method: 'PUT',
      body: JSON.stringify(marketDataSources.value)
    })
    marketDataSourceVisible.value = false
    message.success('行情源配置已保存；下次行情请求将按新优先级故障转移')
  } catch (error) { message.error(error.message) }
  finally { marketDataSourceLoading.value = false }
}

async function saveAiConfig() {
  if (!aiForm.provider.trim() || !aiForm.model.trim() || !aiForm.baseUrl.trim()) return message.warning('请完整填写 AI 供应商、模型和接口地址')
  aiLoading.value = true
  try {
    aiConfig.value = await request('/ai/config', {
      method: 'PUT',
      body: JSON.stringify({ ...aiForm })
    })
    aiForm.apiKey = ''
    aiVisible.value = false
    message.success('AI 接入配置已保存')
  } catch (error) { message.error(error.message) }
  finally { aiLoading.value = false }
}

async function saveAccountAssets() {
  if (!accountForm.totalAssets || accountForm.totalAssets <= 0) return message.warning('请输入大于 0 的账户总资产')
  accountLoading.value = true
  try {
    accountAssets.value = await request('/account/assets', {
      method: 'PUT',
      body: JSON.stringify({ totalAssets: accountForm.totalAssets })
    })
    accountVisible.value = false
    await loadPool()
    message.success('账户总资产已保存，股票池已按新仓位比例重新分析')
  } catch (error) { message.error(error.message) }
  finally { accountLoading.value = false }
}

async function applyPriceFilter() {
  if (recommendMinPrice.value === null || recommendMaxPrice.value === null || recommendMinPrice.value === undefined || recommendMaxPrice.value === undefined) return message.warning('请先填写最低价格和最高价格')
  if (recommendMaxPrice.value < recommendMinPrice.value) return message.warning('最高价格不能低于最低价格')
  recommendationActionLoading.value = true
  startAnalysisProgress('正在保存配置并启动推荐分析')
  try {
    const config = await request('/strategy-config')
    await request('/strategy-config', {
      method: 'PUT',
      body: JSON.stringify({ ...config, minPrice: recommendMinPrice.value, maxPrice: recommendMaxPrice.value })
    })
    const saved = await request(`/recommendations/refresh?asOf=${asOf.value}&minPrice=${recommendMinPrice.value}&maxPrice=${recommendMaxPrice.value}`, { method: 'POST' })
    if (!saved?.snapshot) throw new Error('分析接口未返回有效快照')
    applyRecommendationData(saved)
    notification.success({
      message: '保存并重新分析成功',
      description: `推荐结果已写入数据库（${snapshotTime(saved.snapshot)}）`,
      duration: 5
    })
  } catch (error) {
    notification.error({
      message: '保存并重新分析失败',
      description: error?.message || '请求失败，请检查服务端和网络连接',
      duration: 8
    })
  } finally {
    recommendationActionLoading.value = false
    finishAnalysisProgress()
  }
}

async function refreshRecommendationTable() {
  await loadRecommendations()
}

async function loadPool() {
  poolLoading.value = true
  startAnalysisProgress('正在分析我的股票池')
  try {
    const data = await request(`/managed-pool?asOf=${asOf.value}`)
    poolItems.value = data.items || []
    poolCodes.value = data.codes || []
    poolMarketContext.value = data.marketContext || null
    if (data.marketIndices?.length) marketIndices.value = data.marketIndices
    const selectedCode = selectedPosition.value?.code || selectedPlanned.value?.code
    const refreshed = selectedCode ? poolItems.value.find(item => item.code === selectedCode) : null
    if (refreshed) {
      if (selectedPosition.value?.code === selectedCode) selectedPosition.value = refreshed
      if (selectedPlanned.value?.code === selectedCode) selectedPlanned.value = refreshed
    }
  } catch (error) {
    message.error(error.message)
  } finally {
    poolLoading.value = false
    finishAnalysisProgress()
  }
}

async function loadSystemInitialization() {
  try {
    const result = await request('/system/initialization')
    systemInitializedAt.value = result.initializedAt || null
  } catch (error) {
    message.error(`读取系统初始化状态失败：${error.message}`)
  }
}

async function initializeSystem() {
  systemInitializationLoading.value = true
  startAnalysisProgress('正在执行系统初始化任务')
  try {
    const result = await request(`/system/initialization?asOf=${asOf.value}`, { method: 'POST' })
    systemInitializedAt.value = result.initializedAt || null
    await refreshAll()
    const notify = result.status === 'PARTIAL' ? notification.warning : notification.success
    notify({
      message: '系统初始化完成',
      description: `${result.status === 'PARTIAL' ? '初始化已完成，但部分 AI 缓存未更新；' : '新闻、AI缓存和推荐任务已执行；'}初始化时间：${formatDateTime(systemInitializedAt.value)}`,
      duration: 6
    })
  } catch (error) {
    notification.error({
      message: '系统初始化失败',
      description: error?.message || '初始化任务执行失败，请检查服务端日志',
      duration: 8
    })
  } finally {
    systemInitializationLoading.value = false
    finishAnalysisProgress()
  }
}

async function openPortfolioAnalysis() {
  stopPortfolioAnalysisPolling()
  portfolioAnalysisVisible.value = true
  portfolioAnalysis.value = null
  portfolioAnalysisLoading.value = true
  startAnalysisProgress('正在汇总大盘、全部持仓和已确认交易')
  const pollToken = portfolioAnalysisPollToken
  let waitingForResult = false
  try {
    const result = await request(`/managed-pool/portfolio-analysis/refresh?asOf=${asOf.value}`, { method: 'POST' })
    if (pollToken !== portfolioAnalysisPollToken) return
    portfolioAnalysis.value = result
    if (isPortfolioAnalysisPending(result)) {
      waitingForResult = true
      schedulePortfolioAnalysisPoll(pollToken)
    }
  } catch (error) {
    if (pollToken !== portfolioAnalysisPollToken) return
    portfolioAnalysis.value = { available: false, message: `持仓分析失败：${error.message}` }
  } finally {
    if (!waitingForResult && pollToken === portfolioAnalysisPollToken) finishPortfolioAnalysisPolling()
  }
}

function isPortfolioAnalysisPending(result) {
  return result?.available === false && /后台生成|自动更新/.test(String(result.message || ''))
}

function schedulePortfolioAnalysisPoll(pollToken) {
  if (portfolioAnalysisPollTimer) window.clearTimeout(portfolioAnalysisPollTimer)
  portfolioAnalysisPollTimer = window.setTimeout(() => pollPortfolioAnalysis(pollToken), 2000)
}

async function pollPortfolioAnalysis(pollToken) {
  portfolioAnalysisPollTimer = undefined
  if (pollToken !== portfolioAnalysisPollToken) return
  if (!portfolioAnalysisVisible.value) {
    finishPortfolioAnalysisPolling()
    return
  }
  try {
    const result = await request(`/managed-pool/portfolio-analysis?asOf=${asOf.value}`)
    if (pollToken !== portfolioAnalysisPollToken) return
    portfolioAnalysis.value = result
    if (isPortfolioAnalysisPending(result)) {
      schedulePortfolioAnalysisPoll(pollToken)
      return
    }
  } catch (error) {
    if (pollToken !== portfolioAnalysisPollToken) return
    portfolioAnalysis.value = { available: false, message: `持仓分析查询失败：${error.message}` }
  }
  finishPortfolioAnalysisPolling()
}

function finishPortfolioAnalysisPolling() {
  if (portfolioAnalysisPollTimer) window.clearTimeout(portfolioAnalysisPollTimer)
  portfolioAnalysisPollTimer = undefined
  portfolioAnalysisLoading.value = false
  finishAnalysisProgress()
}

function stopPortfolioAnalysisPolling() {
  portfolioAnalysisPollToken += 1
  finishPortfolioAnalysisPolling()
}

async function refreshAll() {
  await Promise.all([loadRecommendations(), loadPool(), loadAccountAssets(), loadAiConfig(), loadMarketDataSources(), loadScoringModel(), loadModelStatuses()])
}

async function addSelected() {
  if (!selectedStocks.value.length) return message.warning('请先勾选推荐股票')
  const selectedCount = selectedStocks.value.length
  try {
    for (const item of selectedStocks.value) {
      await request(`/managed-pool?asOf=${asOf.value}`, {
        method: 'POST',
        body: JSON.stringify({ code: item.stock.code, name: item.stock.name, industry: item.stock.industry })
      })
    }
    selectedRowKeys.value = []
    await loadPool()
    activeTab.value = 'pool'
    message.success(`已将 ${selectedCount} 只股票加入股票池`)
  } catch (error) { message.error(error.message) }
}

async function addOneStock(item) {
  try {
    await request(`/managed-pool?asOf=${asOf.value}`, {
      method: 'POST',
      body: JSON.stringify({ code: item.stock.code, name: item.stock.name, industry: item.stock.industry })
    })
    await loadPool()
    activeTab.value = 'pool'
    message.success(`${item.stock.name} 已加入股票池`)
  } catch (error) { message.error(error.message) }
}

async function addManual() {
  if (!form.code.trim()) return message.warning('请输入股票代码')
  manualLoading.value = true
  try {
    await request(`/managed-pool?asOf=${asOf.value}`, { method: 'POST', body: JSON.stringify({ ...form }) })
    Object.assign(form, { code: '', name: '', industry: '' })
    manualVisible.value = false
    await loadPool()
    activeTab.value = 'pool'
    message.success('已手工加入股票池')
  } catch (error) { message.error(error.message) }
  finally { manualLoading.value = false }
}

async function removeStock(code) {
  try {
    await request(`/managed-pool/${code}?asOf=${asOf.value}`, { method: 'DELETE' })
    await loadPool()
    message.success('已从股票池移除')
  } catch (error) { message.error(error.message) }
}

async function openPosition(record) {
  selectedPosition.value = record
  positionRecord.value = record.position?.hasPosition ? {
    found: true,
    quantity: record.position.quantity,
    availableQuantity: record.position.quantity,
    avgCost: record.position.buyPrice,
    highestPrice: null,
    openedAt: null,
    updatedAt: null,
    source: 'POOL_ANALYSIS'
  } : null
  positionForm.buyPrice = record.position?.buyPrice ?? null
  positionForm.quantity = record.position?.quantity ?? null
  positionForm.openedAt = record.position?.openedAt ?? asOf.value
  positionVisible.value = true
  positionRecordLoading.value = true
  try {
    positionRecord.value = await request(`/managed-pool/${record.code}/position`)
    // 兼容尚未重启的旧后端：股票池响应本身已经包含当前持仓分析，不能因为新读接口暂未注册而阻断弹框。
    if (!positionRecord.value?.found && record.position?.hasPosition) {
      positionRecord.value = {
        found: true,
        quantity: record.position.quantity,
        availableQuantity: record.position.quantity,
        avgCost: record.position.buyPrice,
        highestPrice: null,
        openedAt: null,
        updatedAt: null,
        source: 'POOL_ANALYSIS'
      }
    }
    if (selectedPosition.value?.code === record.code && positionRecord.value?.found) {
      positionForm.buyPrice = positionRecord.value.avgCost ?? positionForm.buyPrice
      positionForm.quantity = positionRecord.value.quantity ?? positionForm.quantity
      positionForm.openedAt = positionRecord.value.openedAt ?? positionForm.openedAt
    }
  } catch (error) {
    if (error.status !== 405) message.warning(`读取数据库持仓记录失败：${error.message}`)
  } finally {
    positionRecordLoading.value = false
  }
}

function openPlannedOrder(record) {
  selectedPlanned.value = record
  plannedForm.side = record.plannedOrder?.side === 'SELL' ? 'SELL' : 'BUY'
  plannedForm.plannedPrice = record.plannedOrder?.plannedPrice ?? null
  plannedForm.quantity = record.plannedOrder?.quantity ?? null
  plannedForm.tradeDate = record.plannedOrder?.tradeDate ?? asOf.value
  plannedVisible.value = true
}

async function openHistory(record) {
  selectedHistory.value = record
  historyTrades.value = []
  historyVisible.value = true
  historyLoading.value = true
  try {
    historyTrades.value = await request(`/managed-pool/${record.code}/history`)
  } catch (error) {
    message.error(`读取持仓清单失败：${error.message}`)
  } finally { historyLoading.value = false }
}

function historyTimeline(record) {
  if (!record) return []
  const events = [{
    key: `added-${record.code}`,
    date: record.addedAt || '—',
    title: '加入股票池',
    description: record.addedBy === 'MANUAL' ? '手工加入股票池' : '从推荐中心加入股票池',
    color: 'blue'
  }]
  if (record.position?.hasPosition) {
    events.push({
      key: `position-${record.code}`,
      date: '当前',
      title: '当前持仓快照',
      description: `${formatNumber(record.position.quantity)} 股 · 当前市值 ${formatMoney(record.position.marketValue)} · 成本 ${formatMoney(record.position.buyPrice)} · ${record.position.status}`,
      color: record.position.actionColor === 'red' ? 'red' : 'green'
    })
  }
  historyTrades.value.forEach((trade, index) => {
    events.push({
      key: `trade-${trade.tradeDate}-${index}`,
      date: trade.tradeDate || '—',
      title: `已确认${trade.side === 'SELL' ? '卖出' : '买入'}`,
      description: `${formatNumber(trade.quantity)} 股 · 成交价 ${formatMoney(trade.executedPrice)} · ${trade.status || '已记录'}`,
      color: trade.side === 'SELL' ? 'red' : 'green'
    })
  })
  const timelineDate = value => value === '当前' ? '9999-12-31' : String(value)
  return events.sort((left, right) => timelineDate(right.date).localeCompare(timelineDate(left.date)))
}

async function savePlannedOrder() {
  if (!selectedPlanned.value) return
  const sideText = plannedForm.side === 'SELL' ? '卖出' : '买入'
  if (!plannedForm.plannedPrice || plannedForm.plannedPrice <= 0) return message.warning(`请输入大于 0 的计划${sideText}价`)
  if (!plannedForm.quantity || plannedForm.quantity <= 0) return message.warning(`请输入大于 0 的计划${sideText}数量`)
  plannedLoading.value = true
  startMaintenanceProgress('正在保存买卖计划')
  try {
    const result = await request(`/managed-pool/${selectedPlanned.value.code}/planned-order?asOf=${asOf.value}`, {
      method: 'PUT',
      body: JSON.stringify({ side: plannedForm.side, plannedPrice: plannedForm.plannedPrice, quantity: plannedForm.quantity, tradeDate: plannedForm.tradeDate })
    })
    selectedPlanned.value = { ...selectedPlanned.value, plannedOrder: result }
    await loadPool()
    selectedPlanned.value = poolItems.value.find(item => item.code === selectedPlanned.value.code) || selectedPlanned.value
    message.success('买卖计划已保存到数据库，并完成分析')
  } catch (error) { message.error(error.message) }
  finally { plannedLoading.value = false; finishMaintenanceProgress() }
}

async function analyzePlannedOrder() {
  if (!selectedPlanned.value) return
  const sideText = plannedForm.side === 'SELL' ? '卖出' : '买入'
  if (!plannedForm.plannedPrice || plannedForm.plannedPrice <= 0) return message.warning(`请输入大于 0 的计划${sideText}价`)
  if (!plannedForm.quantity || plannedForm.quantity <= 0) return message.warning(`请输入大于 0 的计划${sideText}数量`)
  plannedLoading.value = true
  startMaintenanceProgress('正在分析计划')
  try {
    const result = await request(`/managed-pool/${selectedPlanned.value.code}/planned-order/analyze?asOf=${asOf.value}`, {
      method: 'POST',
      body: JSON.stringify({ side: plannedForm.side, plannedPrice: plannedForm.plannedPrice, quantity: plannedForm.quantity, tradeDate: plannedForm.tradeDate })
    })
    selectedPlanned.value = { ...selectedPlanned.value, plannedOrder: result }
    message.success('计划分析完成，未保存计划')
  } catch (error) { message.error(error.message) }
  finally { plannedLoading.value = false; finishMaintenanceProgress() }
}

async function confirmPlannedOrder() {
  if (!selectedPlanned.value?.plannedOrder?.hasPlan) return message.warning('请先分析计划')
  plannedLoading.value = true
  startMaintenanceProgress('正在确认今日交易')
  try {
    await request(`/managed-pool/${selectedPlanned.value.code}/planned-order/confirm?asOf=${asOf.value}`, {
      method: 'POST',
      body: JSON.stringify({ side: plannedForm.side, plannedPrice: plannedForm.plannedPrice, quantity: plannedForm.quantity, tradeDate: plannedForm.tradeDate })
    })
    await loadPool()
    selectedPlanned.value = poolItems.value.find(item => item.code === selectedPlanned.value.code) || selectedPlanned.value
    message.success('今日交易已记录，并已合并到历史持仓分析')
  } catch (error) { message.error(error.message) }
  finally { plannedLoading.value = false; finishMaintenanceProgress() }
}

async function clearPlannedOrder() {
  if (!selectedPlanned.value) return
  plannedLoading.value = true
  startMaintenanceProgress('正在清除计划操作')
  try {
    await request(`/managed-pool/${selectedPlanned.value.code}/planned-order?asOf=${asOf.value}`, { method: 'DELETE' })
    plannedVisible.value = false
    await loadPool()
    message.success('计划操作已清除，历史持仓不会受影响')
  } catch (error) { message.error(error.message) }
  finally { plannedLoading.value = false; finishMaintenanceProgress() }
}

async function savePosition() {
  if (!selectedPosition.value) return
  if (!positionForm.buyPrice || positionForm.buyPrice <= 0) return message.warning('请输入大于 0 的买入价')
  if (!positionForm.quantity || positionForm.quantity <= 0) return message.warning('请输入大于 0 的买入数量')
  positionLoading.value = true
  startMaintenanceProgress('正在保存持仓')
  try {
    await request(`/managed-pool/${selectedPosition.value.code}/position?asOf=${asOf.value}`, {
      method: 'PUT',
      body: JSON.stringify({ buyPrice: positionForm.buyPrice, quantity: positionForm.quantity, openedAt: positionForm.openedAt })
    })
    positionVisible.value = false
    await loadPool()
    message.success('持仓成本和数量已保存，并完成持仓分析')
  } catch (error) { message.error(error.message) }
  finally { positionLoading.value = false; finishMaintenanceProgress() }
}

async function clearPosition() {
  if (!selectedPosition.value) return
  positionLoading.value = true
  startMaintenanceProgress('正在清除持仓记录')
  try {
    await request(`/managed-pool/${selectedPosition.value.code}/position?asOf=${asOf.value}`, { method: 'DELETE' })
    positionVisible.value = false
    await loadPool()
    message.success('持仓记录已清除')
  } catch (error) { message.error(error.message) }
  finally { positionLoading.value = false; finishMaintenanceProgress() }
}

function formatNumber(value) { return value === null || value === undefined ? '—' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 3 }) }
function formatMoney(value) { return value === null || value === undefined ? '—' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 3, maximumFractionDigits: 3 }) }
function formatHoldingPnl(value) {
  if (value === null || value === undefined) return '—'
  const amount = Number(value)
  return `${amount < 0 ? '-' : ''}${Math.abs(amount).toLocaleString('zh-CN', { minimumFractionDigits: 3, maximumFractionDigits: 3 })}`
}
function formatPrice(value) { return value === null || value === undefined ? '联网失败' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 3, maximumFractionDigits: 3 }) }
function indexQuote(code) { return marketIndices.value.find(item => item.code === code) || null }
function formatIndexPrice(quote) { return quote?.price === null || quote?.price === undefined ? '联网失败' : Number(quote.price).toLocaleString('zh-CN', { minimumFractionDigits: 3, maximumFractionDigits: 3 }) }
function formatIndexChange(quote) {
  if (quote?.change === null || quote?.change === undefined || quote?.changePercent === null || quote?.changePercent === undefined) return '联网失败'
  const change = Number(quote.change)
  const percent = Number(quote.changePercent)
  return `${change > 0 ? '+' : ''}${change.toFixed(2)}点 / ${percent > 0 ? '+' : ''}${percent.toFixed(2)}%`
}
function indexClass(quote) { return !quote || quote.price === null ? 'network-fail' : Number(quote.changePercent) >= 0 ? 'rise' : 'fall' }
function indexStatus(quote) { return quote?.quoteStatus === 'REALTIME' ? `实时 · ${quote.quoteTime ? String(quote.quoteTime).replace('T', ' ').slice(0, 16) : '—'}` : '联网失败' }
function snapshotTime(snapshot) { return snapshot?.generatedAt ? String(snapshot.generatedAt).replace('T', ' ').slice(0, 16) : '暂无' }
function snapshotColor(snapshot) { return snapshot?.stale ? 'orange' : snapshot?.latestSlot ? 'green' : 'blue' }
function snapshotLabel(snapshot) {
  if (!snapshot) return '数据库暂无推荐快照'
  if (snapshot.stale) return `数据更新：${snapshotTime(snapshot)} · 未完成最近时点 ${snapshot.expectedSlot || '—'}`
  return `数据更新：${snapshotTime(snapshot)} · ${snapshot.source === 'SCHEDULED' ? `时点 ${snapshot.slot}` : '手动刷新'}`
}
function formatChange(value) {
  if (value === null || value === undefined) return '—'
  const number = Number(value)
  return `${number > 0 ? '+' : ''}${number.toFixed(2)}%`
}
function formatPercent(value) { return `${(Number(value || 0) * 100).toFixed(0)}%` }
function accuracyMetric(accuracy, type) {
  const prefix = type === 'operation' ? 'operation' : 'prediction'
  return {
    samples: accuracy?.[`${prefix}Samples`] || 0,
    correct: accuracy?.[`${prefix}Correct`] || 0,
    rate: accuracy?.[`${prefix}Rate`],
    method: accuracy?.[`${prefix}Method`]
  }
}
function accuracyMetricText(metric) {
  if (!metric?.samples) return '暂无样本'
  return metric.rate === null || metric.rate === undefined ? `样本不足 · ${metric.samples}个` : `${Number(metric.rate).toFixed(1)}%`
}
function accuracyMetricColor(metric) {
  if (metric?.rate === null || metric?.rate === undefined) return 'default'
  const rate = Number(metric.rate)
  return rate >= 60 ? 'green' : rate >= 50 ? 'orange' : 'red'
}
function accuracyMetricMeta(metric) {
  return metric?.samples ? `命中 ${metric.correct}/${metric.samples}` : '暂无样本'
}
function accuracyCalculatedAt(accuracy) {
  if (!accuracy?.calculatedAt) return '尚未手动计算'
  return String(accuracy.calculatedAt).replace('T', ' ').slice(0, 19)
}
function scoreColor(score) { return score >= 80 ? 'blue' : 'default' }
function recommendationStars(score) {
  const value = Number(score)
  if (!Number.isFinite(value)) return 1
  return value >= 90 ? 5 : value >= 80 ? 4 : value >= 70 ? 3 : value >= 60 ? 2 : 1
}
function recommendationStarClass(score) { return `stars-${recommendationStars(score)}` }
function riskColor(plan) { return plan.riskColor || 'default' }
function riskText(level) { return level === 'HIGH' ? '高风险' : level === 'MEDIUM' ? '中风险' : '低风险' }
function planDecisionAlertType(decision) {
  if (decision === '可以考虑买入' || decision === '可以考虑卖出') return 'success'
  if (decision === '无法买入' || decision === '无法卖出') return 'error'
  return 'warning'
}
function canConfirmPlannedOrder(order, todayTrade) {
  return Boolean(order?.hasPlan) && !todayTrade?.hasTrade && !String(order?.decision || '').startsWith('无法')
}
function planDecisionHint(decision) {
  if (String(decision || '').startsWith('无法')) return '该计划触发了执行限制，修正价格或行情后再分析。'
  return '“成功率低 / 不建议”仍可手动确认，但代表模型不推荐当前计划；系统不会自动下单。'
}
function scoreOf(record) { return record.analysis?.scores?.finalScore ?? record.scores?.finalScore ?? 0 }
function quoteStatus(stock) { return stock.quoteStatus === 'REALTIME' ? '实时' : '联网失败' }
function quoteTime(stock) { return stock.quoteTime ? String(stock.quoteTime).replace('T', ' ').slice(0, 16) : '—' }
function dataStatusText(stock) { return stock.dataStatus === 'FULL' ? '分析数据完整' : stock.dataStatus === 'TECHNICAL_ONLY' ? '技术数据可用·基本面待补齐' : '仅实时价格' }
function eventColor(type) { return type === '利好' ? 'green' : type === '利空' ? 'red' : 'default' }
function eventTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '暂无时间' }
function formatDateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '尚未初始化' }
function formatGap(value) { return value === null || value === undefined ? '—' : `${Number(value).toFixed(2)}%` }
function formatPercentValue(value) { return value === null || value === undefined ? '—' : `${Number(value).toFixed(2)}%` }
function positionRatioClass(record) {
  const current = Number(record.position?.positionPercent)
  const max = Number(record.position?.maxPositionPercent)
  if (!Number.isFinite(current) || !Number.isFinite(max) || max <= 0) return 'position-unset'
  if (current > max) return 'position-over'
  if (current >= max * 0.8) return 'position-near'
  return 'position-normal'
}
function tradeAction(record) {
  const position = record.position
  const plan = record.tradePlan
  if (position?.hasPosition && position.action === '优先止损') {
    return { type: 'urgent', icon: '⚠', label: '强烈建议卖出' }
  }
  if (position?.hasPosition && ['保护利润', '分批止盈'].includes(position.action)) {
    return { type: 'sell', icon: '✋', label: position.action }
  }
  if (!position?.hasPosition && plan?.signal === '分批买入' && record.analysis?.hardFilter?.passed) {
    return { type: 'urgent', icon: '⚠', label: '强烈建议买入' }
  }
  return null
}
function operationAdvice(record) {
  if (record.position?.hasPosition && record.position.action && !['继续持有', '暂不判断仓位'].includes(record.position.action)) return record.position.action
  return record.tradePlan?.signal || '等待确认'
}
function operationAdviceColor(record) {
  const advice = operationAdvice(record)
  if (['强烈建议卖出', '优先止损', '保护利润', '分批止盈', '禁止交易', '联网失败', '数据不足'].includes(advice)) return 'red'
  if (['分批买入', '小仓试探'].includes(advice)) return 'green'
  return 'blue'
}
function buyAdvice(record) {
  const current = Number(record.analysis?.stock?.price)
  const low = record.tradePlan?.buyLow
  const high = record.tradePlan?.buyHigh
  if (low === null || low === undefined || high === null || high === undefined || !Number.isFinite(current)) return '暂无真实价格'
  const support = Number(record.tradePlan?.nextSupportPrice)
  if (current < Number(low)) {
    if (Number.isFinite(support) && current >= support) return `关注承接 ${formatNumber(support)}`
    return Number.isFinite(support) ? `跌破承接 ${formatNumber(support)}，等待止跌` : `等待止跌（参考 ${formatNumber(low)}）`
  }
  if (current > Number(high)) return `等回踩 ${formatNumber(high)}`
  return `${formatNumber(low)} — ${formatNumber(high)}`
}
function buyAdviceClass(record) {
  const current = Number(record.analysis?.stock?.price)
  const low = Number(record.tradePlan?.buyLow)
  const high = Number(record.tradePlan?.buyHigh)
  const support = Number(record.tradePlan?.nextSupportPrice)
  if (!Number.isFinite(current) || !Number.isFinite(low) || !Number.isFinite(high)) return 'price-card network-fail'
  if (current < low && Number.isFinite(support) && current < support) return 'price-card price-red'
  return current >= low && current <= high ? 'price-card price-green' : 'price-card price-orange'
}
function historyPoints(record) {
  return (record.priceHistory || []).filter(point => point?.date && Number.isFinite(Number(point.price)))
}
function chartReferences(record) {
  const references = []
  const positionBuy = Number(record.position?.buyPrice)
  const plannedBuy = Number(record.plannedOrder?.plannedPrice)
  const plannedSide = record.plannedOrder?.side === 'SELL' ? '卖出' : '买入'
  const suggestedBuy = Number(record.tradePlan?.buyLow)
  if (Number.isFinite(positionBuy)) references.push({ key: 'position-buy', label: '买入价', value: positionBuy, color: '#1677ff', dash: '5 4' })
  if (Number.isFinite(plannedBuy)) references.push({ key: 'planned-order', label: `计划${plannedSide}价`, value: plannedBuy, color: plannedSide === '卖出' ? '#cf1322' : '#1677ff', dash: '5 4' })
  if (!Number.isFinite(positionBuy) && !Number.isFinite(plannedBuy) && Number.isFinite(suggestedBuy)) references.push({ key: 'suggested-buy', label: '建议买入价', value: suggestedBuy, color: '#1677ff', dash: '5 4' })
  const nextSupport = Number(record.tradePlan?.nextSupportPrice)
  if (Number.isFinite(nextSupport)) references.push({ key: 'next-support', label: '下一承接价', value: nextSupport, color: '#94a3b8', dash: '3 3' })
  const takeProfit1 = Number(record.tradePlan?.takeProfit1)
  const takeProfit2 = Number(record.tradePlan?.takeProfit2)
  if (Number.isFinite(takeProfit1)) references.push({ key: 'take-profit-1', label: '卖出价1', value: takeProfit1, color: '#cf1322', dash: '6 4' })
  if (Number.isFinite(takeProfit2)) references.push({ key: 'take-profit-2', label: '卖出价2', value: takeProfit2, color: '#cf1322', dash: '2 4' })
  const forecast = Number(record.tradePlan?.forecastPrice)
  if (Number.isFinite(forecast)) references.push({ key: 'forecast', label: '推测价', value: forecast, color: '#94a3b8', dash: '8 4' })
  return references
}
function roundedPrice(value) { return Number(Number(value).toFixed(3)) }
function roundedPercent(value) { return Number(Number(value).toFixed(2)) }
function priceLadder(start, end) {
  if (!Number.isFinite(start) || !Number.isFinite(end)) return []
  const low = Math.min(start, end)
  const high = Math.max(start, end)
  return Array.from({ length: 5 }, (_, index) => roundedPrice(low + (high - low) * index / 4))
}
function buyPriceLevels(record) {
  const support = Number(record.tradePlan?.nextSupportPrice)
  const low = Number(record.tradePlan?.buyLow)
  const high = Number(record.tradePlan?.buyHigh)
  if (!Number.isFinite(low) || !Number.isFinite(high)) return []
  return priceLadder(Number.isFinite(support) ? support : low, high)
}
function sellPriceLevels(record) {
  const first = Number(record.tradePlan?.takeProfit1)
  const second = Number(record.tradePlan?.takeProfit2)
  if (!Number.isFinite(first) || !Number.isFinite(second)) return []
  return priceLadder(first, second)
}

const STOCK_LOT_SIZE = 100
const BUY_COMMISSION_RATE = 0.0003
const BUY_COMMISSION_MIN = 5
const BUY_TRANSFER_FEE_RATE = 0.00001
const DEFAULT_BUY_WEIGHTS = [0.40, 0.25, 0.15, 0.10, 0.10]

function buyCost(price, quantity) {
  const amount = price * quantity
  const commission = Math.max(BUY_COMMISSION_MIN, amount * BUY_COMMISSION_RATE)
  return amount + commission + amount * BUY_TRANSFER_FEE_RATE
}

function remainingCash(totalAssets) {
  if (!Number.isFinite(totalAssets) || totalAssets <= 0) return null
  const committed = poolItems.value.reduce((sum, item) => {
    const positionValue = Number(item.position?.marketValue)
    const currentPrice = Number(item.analysis?.stock?.price)
    const holdingQuantity = Number(item.position?.quantity)
    const holdingCost = Number.isFinite(positionValue) && positionValue > 0
      ? positionValue
      : Number.isFinite(currentPrice) && currentPrice > 0 && Number.isFinite(holdingQuantity) && holdingQuantity > 0
        ? currentPrice * holdingQuantity : 0
    const plannedPrice = item.plannedOrder?.side === 'BUY' ? Number(item.plannedOrder.plannedPrice) : 0
    const plannedQuantity = item.plannedOrder?.side === 'BUY' ? Number(item.plannedOrder.quantity) : 0
    const plannedCost = Number.isFinite(plannedPrice) && plannedPrice > 0 && Number.isFinite(plannedQuantity) && plannedQuantity > 0
      ? buyCost(plannedPrice, plannedQuantity) : 0
    return sum + holdingCost + plannedCost
  }, 0)
  return Math.max(0, totalAssets - committed)
}

function quantityForBudget(budget, price) {
  if (!Number.isFinite(price) || price <= 0) return STOCK_LOT_SIZE
  if (!Number.isFinite(budget) || budget <= 0) return STOCK_LOT_SIZE
  let quantity = Math.floor(budget / price / STOCK_LOT_SIZE) * STOCK_LOT_SIZE
  quantity = Math.max(STOCK_LOT_SIZE, quantity)
  while (quantity > STOCK_LOT_SIZE && buyCost(price, quantity) > budget) quantity -= STOCK_LOT_SIZE
  return Math.max(STOCK_LOT_SIZE, quantity)
}

function suggestedQuantityText(quantity) {
  const normalized = Number(quantity)
  return `${formatNumber(Number.isFinite(normalized) && normalized > 0 ? normalized : STOCK_LOT_SIZE)}股`
}
function fallbackBuyWeights(action) {
  if (action === '小仓试探') return [0.50, 0.30, 0.20, 0, 0]
  if (action === '持有') return [0.40, 0.30, 0.20, 0.10, 0]
  if (action === '谨慎持有') return [0.50, 0.30, 0.20, 0, 0]
  return DEFAULT_BUY_WEIGHTS
}
function buyLevelWeights(record) {
  const weights = record.tradePlan?.positionPlan?.suggestedBuyWeights
  const rawWeights = Array.isArray(weights) && weights.length >= 5
    ? weights.slice(0, 5).map(value => Math.max(0, Number(value) || 0))
    : fallbackBuyWeights(record.tradePlan?.positionPlan?.action || record.position?.action)
  const smallestPositive = rawWeights.filter(value => value > 0).sort((left, right) => left - right)[0]
  const minimumWeight = smallestPositive ? Math.max(0.05, smallestPositive * 0.5) : 0.10
  return rawWeights.map(value => value > 0 ? value : minimumWeight)
}
function buySuggestedQuantities(record, prices = buyPriceLevels(record)) {
  const totalAssets = Number(accountAssets.value?.totalAssets)
  const suggestedAddRaw = record.tradePlan?.positionPlan?.suggestedAddPercent
  const suggestedAddPercent = suggestedAddRaw === null || suggestedAddRaw === undefined ? NaN : Number(suggestedAddRaw)
  const validPrices = prices.map(value => Number(value))
  if (validPrices.length < 5 || validPrices.some(value => !Number.isFinite(value) || value <= 0)) return Array(5).fill(STOCK_LOT_SIZE)
  const weights = buyLevelWeights(record)
  const weightTotal = weights.reduce((sum, value) => sum + value, 0)
  const plannedPrice = record.plannedOrder?.side === 'BUY' ? Number(record.plannedOrder.plannedPrice) : 0
  const plannedQuantity = record.plannedOrder?.side === 'BUY' ? Number(record.plannedOrder.quantity) : 0
  const plannedCost = Number.isFinite(plannedPrice) && Number.isFinite(plannedQuantity)
    && plannedPrice > 0 && plannedQuantity > 0 ? buyCost(plannedPrice, plannedQuantity) : 0
  const cash = remainingCash(totalAssets)
  const addOnBudget = Number.isFinite(suggestedAddPercent) && suggestedAddPercent > 0
    ? totalAssets * suggestedAddPercent / 100 - plannedCost : 0
  // 仓位模型只决定优先级；只要账户仍有现金，就必须给出可执行的正数数量。
  // 资金不足一手时仍给出一手，允许把手续费和整手约束造成的小幅超出纳入建议。
  const fallbackBudget = validPrices[0] * STOCK_LOT_SIZE
  const totalBudget = cash !== null && cash > 0
    ? (addOnBudget > 0 ? Math.min(cash, addOnBudget) : cash)
    : fallbackBudget
  const quantities = Array(5).fill(STOCK_LOT_SIZE)
  for (let index = 0; index < 5; index += 1) {
    const targetBudget = totalBudget * weights[index] / weightTotal
    quantities[index] = quantityForBudget(targetBudget, validPrices[index])
  }
  return quantities
}
function buySuggestedQuantity(record, price, index = buyPriceLevels(record).findIndex(level => Number(level) === Number(price))) {
  const quantities = buySuggestedQuantities(record)
  return quantities[Math.max(0, index)] || STOCK_LOT_SIZE
}
function sellSuggestedQuantity(record, index) {
  if (!record.position?.hasPosition) return STOCK_LOT_SIZE
  const quantity = Number(record.position.quantity)
  if (!Number.isFinite(quantity) || quantity <= 0) return STOCK_LOT_SIZE
  const lots = Math.floor(quantity / STOCK_LOT_SIZE) * STOCK_LOT_SIZE
  if (index === 4) return Math.max(STOCK_LOT_SIZE, quantity - Math.floor(lots / 5) * 4)
  return Math.max(STOCK_LOT_SIZE, Math.floor(lots / 5 / STOCK_LOT_SIZE) * STOCK_LOT_SIZE)
}
function currentPriceClass(record) {
  const current = Number(record.analysis?.stock?.price)
  const support = Number(record.tradePlan?.nextSupportPrice)
  const low = Number(record.tradePlan?.buyLow)
  const high = Number(record.tradePlan?.buyHigh)
  const tp1 = Number(record.tradePlan?.takeProfit1)
  if (!Number.isFinite(current)) return 'current-status-fail'
  if (Number.isFinite(support) && current < support) return 'current-status-danger'
  if (Number.isFinite(low) && current < low) return 'current-status-warning'
  if (Number.isFinite(high) && current <= high) return 'current-status-buy'
  if (Number.isFinite(tp1) && current >= tp1) return 'current-status-sell'
  return 'current-status-observe'
}
function currentPriceHint(record) {
  const current = Number(record.analysis?.stock?.price)
  const support = Number(record.tradePlan?.nextSupportPrice)
  const low = Number(record.tradePlan?.buyLow)
  const high = Number(record.tradePlan?.buyHigh)
  const tp1 = Number(record.tradePlan?.takeProfit1)
  if (!Number.isFinite(current)) return '联网失败'
  if (Number.isFinite(support) && current < support) return `跌破承接 ${formatNumber(support)}`
  if (Number.isFinite(low) && current < low) return `观察承接 ${formatNumber(support)}`
  if (Number.isFinite(high) && current <= high) return '位于建议买入区'
  if (Number.isFinite(tp1) && current >= tp1) return '接近/达到止盈区'
  return '持有观察区'
}
function holdingProfitForecast(record) {
  if (!record.position?.hasPosition) return null
  const current = Number(record.analysis?.stock?.price)
  const cost = Number(record.position.buyPrice)
  const quantity = Number(record.position.quantity)
  if (![current, cost, quantity].every(Number.isFinite) || quantity <= 0 || cost <= 0) return null
  const profit = (current - cost) * quantity
  return {
    current,
    cost,
    quantity,
    currentProfit: roundedPrice(profit),
    currentProfitPercent: roundedPercent((current - cost) / cost * 100),
    levels: sellPriceLevels(record).map(price => ({ price, profit: roundedPrice((price - cost) * quantity), percent: roundedPercent((price - cost) / cost * 100) }))
  }
}
function chartAverage(record) {
  const points = historyPoints(record)
  if (!points.length) return null
  return points.reduce((sum, point) => sum + Number(point.price), 0) / points.length
}
function chartBounds(record) {
  const points = historyPoints(record)
  const values = [...points.map(point => Number(point.price)), ...chartReferences(record).map(line => line.value)]
  if (!values.length) return { min: 0, max: 1 }
  const min = Math.min(...values)
  const max = Math.max(...values)
  const padding = Math.max((max - min) * 0.12, max * 0.015, 0.1)
  return { min: min - padding, max: max + padding }
}
function chartX(record, index) {
  const points = historyPoints(record)
  return points.length <= 1 ? 360 : 48 + (index / (points.length - 1)) * 650
}
function chartY(record, value) {
  const bounds = chartBounds(record)
  return 18 + ((bounds.max - Number(value)) / (bounds.max - bounds.min)) * 220
}
function chartPath(record) {
  return historyPoints(record).map((point, index) => `${index ? 'L' : 'M'} ${chartX(record, index).toFixed(2)} ${chartY(record, point.price).toFixed(2)}`).join(' ')
}
function chartDateIndexes(record) {
  const length = historyPoints(record).length
  if (!length) return []
  return [...new Set([0, Math.floor((length - 1) / 2), length - 1])]
}
function chartDate(record, index) { return String(historyPoints(record)[index]?.date || '').slice(5) }
function chartGridValues(record) {
  const bounds = chartBounds(record)
  return [bounds.max, (bounds.max + bounds.min) / 2, bounds.min]
}
function openEvent(stock) { selectedEvent.value = stock; eventVisible.value = true }

onMounted(async () => {
  await Promise.all([refreshAll(), loadSystemInitialization()])
})

onBeforeUnmount(() => {
  if (analysisProgressTimer) window.clearInterval(analysisProgressTimer)
  if (maintenanceProgressTimer) window.clearInterval(maintenanceProgressTimer)
  if (portfolioAnalysisPollTimer) window.clearTimeout(portfolioAnalysisPollTimer)
})
</script>

<template>
  <a-layout class="app-layout">
    <a-layout-header class="app-header">
      <div class="brand"><div class="brand-mark" aria-label="葫芦神标识"><img class="gourd-logo" src="/gourd-cloud.svg" alt="云上葫芦" /></div><div class="brand-copy"><div class="brand-title">葫芦神</div><div class="brand-subtitle">股票分析系统 · 推荐只做参考，只有手选或手工添加的股票才会进入分析池。</div></div></div>
      <div class="header-actions"><a-tag class="secondary-tag" :color="systemInitializedAt ? 'green' : 'orange'">初始化时间：{{ formatDateTime(systemInitializedAt) }}</a-tag><a-tag class="secondary-tag" :color="accountAssets?.totalAssets ? 'green' : 'orange'">总资产：{{ accountAssets?.totalAssets ? formatNumber(accountAssets.totalAssets) : '未设置' }}</a-tag><a-tag class="secondary-tag" :color="aiConfig?.enabled && aiConfig?.apiKeyConfigured ? 'green' : 'orange'">AI：{{ aiConfig?.provider || '未配置' }} / {{ aiConfig?.model || '—' }}</a-tag><a-button type="primary" :loading="systemInitializationLoading" @click="initializeSystem">系统初始化</a-button><a-button @click="aiVisible = true">维护 AI 接入</a-button><a-button @click="marketDataSourceVisible = true">维护行情源</a-button><a-button @click="accountVisible = true">维护账户资产</a-button></div>
    </a-layout-header>

    <a-layout-content class="content">
      <div v-if="analysisBusy" class="analysis-progress-panel" role="status" aria-live="polite">
        <div class="analysis-progress-head"><div class="analysis-progress-title"><a-spin size="small" /> <span>{{ analysisStage }}</span></div><span class="analysis-progress-time">已耗时 {{ analysisElapsed }} 秒</span></div>
        <a-progress :percent="analysisProgress" :show-info="false" status="active" size="small" />
        <div class="analysis-progress-hint">分析可能需要一些时间，AI 正在批量处理数据；页面会在完成后自动更新，请不要重复点击。</div>
      </div>
      <a-row :gutter="16" class="stat-row">
        <a-col :xs="24" :sm="12" :lg="4"><a-card class="stat-card"><a-spin :spinning="loading" size="small"><a-statistic title="推荐股票" :value="recommendations.length" suffix="只" /></a-spin></a-card></a-col>
        <a-col :xs="24" :sm="12" :lg="4"><a-card class="stat-card"><a-spin :spinning="loading" size="small"><a-statistic title="推荐达标" :value="candidateCount" suffix="只" value-style="color:#1677ff" /></a-spin></a-card></a-col>
        <a-col :xs="24" :sm="12" :lg="4"><a-card class="stat-card"><a-spin :spinning="poolLoading" size="small"><a-statistic title="我的股票池" :value="poolItems.length" suffix="只" value-style="color:#1677ff" /></a-spin></a-card></a-col>
        <a-col :xs="24" :sm="12" :lg="4"><a-card class="stat-card"><a-spin :spinning="poolLoading" size="small"><a-statistic title="平均评分" :value="avgScore" suffix="分" /><a-badge :status="majorEventCount ? 'warning' : 'success'" :text="majorEventCount ? `${majorEventCount} 只重大事件` : '暂无重大事件'" /></a-spin></a-card></a-col>
        <a-col :xs="24" :sm="12" :lg="4"><a-card class="stat-card index-stat-card"><a-spin :spinning="loading || poolLoading" size="small"><div class="index-card-title">上证指数 <span>000001</span></div><strong :class="['index-value', indexClass(indexQuote('000001'))]">{{ formatIndexPrice(indexQuote('000001')) }}</strong><div :class="['index-change', indexClass(indexQuote('000001'))]">{{ formatIndexChange(indexQuote('000001')) }}</div><div class="index-meta">{{ indexStatus(indexQuote('000001')) }}</div></a-spin></a-card></a-col>
        <a-col :xs="24" :sm="12" :lg="4"><a-card class="stat-card index-stat-card"><a-spin :spinning="loading || poolLoading" size="small"><div class="index-card-title">深证成指 <span>399001</span></div><strong :class="['index-value', indexClass(indexQuote('399001'))]">{{ formatIndexPrice(indexQuote('399001')) }}</strong><div :class="['index-change', indexClass(indexQuote('399001'))]">{{ formatIndexChange(indexQuote('399001')) }}</div><div class="index-meta">{{ indexStatus(indexQuote('399001')) }}</div></a-spin></a-card></a-col>
      </a-row>

      <a-card class="main-card">
        <a-tabs v-model:activeKey="activeTab">
          <a-tab-pane key="models" :tab="`模型中心（${systemModelCount}个）`">
            <div class="models-hero">
              <div>
                <div class="models-kicker">MODEL CENTER · SYSTEM OVERVIEW</div>
                <h2>系统模型清单</h2>
                <p>当前按独立决策模块统计，共 {{ systemModelCount }} 个模型。点击任意模型可查看输入、输出、评分口径和系统位置。</p>
              </div>
              <div class="models-hero-score"><strong>{{ systemModelCount }}</strong><span>个独立模型</span></div>
            </div>
            <a-alert class="model-score-note" type="info" show-icon message="模型评分与定时任务" description="评分为系统能力评估，综合考虑覆盖能力、可解释性、数据完整度和稳定性；不是历史收益率，也不构成投资承诺。定时任务按上海时间每小时 00 分新闻、05 分模型、15 分数据执行；推荐检查会避开这三个时间点。" />
            <a-row :gutter="[14, 14]" class="model-grid">
              <a-col v-for="model in systemModels" :key="model.key" :xs="24" :sm="12" :lg="8">
                <a-card class="model-card" hoverable @click="openModelDetail(model)">
                  <div class="model-card-head">
                    <div class="model-icon" :class="`model-icon-${model.color}`">{{ modelDisplayName(model).slice(0, 1) }}</div>
                    <div class="model-card-title"><div>{{ modelDisplayName(model) }}</div><span>{{ model.type }}</span></div>
                    <a-tag :color="modelStatusColor(model)">{{ modelStatus(model) }}</a-tag>
                  </div>
                  <p class="model-card-summary">{{ modelDisplaySummary(model) }}</p>
                  <div class="model-score-line"><span>{{ model.key === 'stock-score' ? '模型可信度' : '真实数据覆盖' }}</span><strong>{{ modelDisplayScore(model) }}</strong><span>/ 100</span></div>
                  <a-progress :percent="modelDisplayScore(model)" :show-info="false" stroke-color="#2563eb" size="small" />
                  <div class="model-card-footer"><span :title="`${modelGeneratedAt(model)} · ${modelStatusInfo(model)?.dataSource || ''}`">{{ modelEvidence(model) }}</span><a-space size="small"><a-button type="link" size="small" :loading="modelRegeneratingKey === model.key" :disabled="Boolean(modelRegeneratingKey || modelDataGeneratingKey)" @click.stop="regenerateModel(model)">重新生成模型</a-button><a-button type="link" size="small" :loading="modelDataGeneratingKey === model.key" :disabled="Boolean(modelRegeneratingKey || modelDataGeneratingKey)" @click.stop="generateModelData(model)">生成数据</a-button><a-button type="link" size="small" @click.stop="openModelDetail(model)">查看 →</a-button></a-space></div>
                </a-card>
              </a-col>
            </a-row>
          </a-tab-pane>
          <a-tab-pane key="recommendations" tab="推荐中心">
            <a-card v-if="marketContext" class="market-context-card" size="small"><a-space wrap><a-tag :color="marketContext.regime === 'BULL' ? 'red' : marketContext.regime === 'BEAR' ? 'green' : 'blue'">市场情绪 {{ marketContext.sentimentAvailable ? marketContext.sentimentScore : '不可用' }}</a-tag><a-tag>环境 {{ marketContext.regime === 'BULL' ? '偏强' : marketContext.regime === 'BEAR' ? '偏弱' : marketContext.regime === 'NORMAL' ? '震荡' : '未知' }}</a-tag><span v-if="marketContext.sentimentAvailable" class="market-context-text">上涨 {{ marketContext.risingCount }} · 下跌 {{ marketContext.fallingCount }} · 涨停 {{ marketContext.limitUpCount }} · 跌停 {{ marketContext.limitDownCount }} · 平均涨跌 {{ formatChange(marketContext.averageChangePercent) }}</span><a-tag :color="marketContext.newsAvailable ? 'purple' : 'default'">新闻热点：{{ marketContext.newsAvailable ? `${marketContext.positiveNewsCount} 利好 / ${marketContext.negativeNewsCount} 利空` : '数据不可用，不参与评分' }}</a-tag></a-space><div class="market-context-hint">{{ marketContext.highlights?.join('；') }}</div></a-card>
            <div class="toolbar"><div><a-space wrap><span class="toolbar-label">价格区间</span><a-input-number v-model:value="recommendMinPrice" :min="0.001" :max="100000" :precision="3" :step="1" addon-before="≥" addon-after="元" class="price-limit" /><a-input-number v-model:value="recommendMaxPrice" :min="0.001" :max="100000" :precision="3" :step="10" addon-before="≤" addon-after="元" class="price-limit" /><a-button type="primary" :loading="loading || recommendationActionLoading" @click="applyPriceFilter">保存并重新分析</a-button><a-button type="primary" ghost :disabled="!selectedStocks.length" @click="addSelected">加入我的股票池<span v-if="selectedStocks.length">（{{ selectedStocks.length }}）</span></a-button><a-button @click="manualVisible = true">手工添加</a-button><a-button :loading="loading" :disabled="recommendationActionLoading" @click="refreshRecommendationTable">刷新数据</a-button></a-space><div class="toolbar-tip">页面刷新仅读取数据库快照，不自动分析。可推荐沪深主板 {{ recommendUniverseCount }} 只；已在我的股票池中的股票不再推荐 · 仅展示约 10–20 只</div></div><a-space wrap><a-tag :color="snapshotColor(recommendationSnapshot)">{{ snapshotLabel(recommendationSnapshot) }}</a-tag><a-tag color="blue">100 分模型</a-tag></a-space></div>
            <a-table class="desktop-table" :row-selection="rowSelection" :row-key="record => record.stock.code" :columns="recommendationColumns" :data-source="recommendations" :loading="loading" :pagination="{ pageSize: 8, showSizeChanger: false }" size="middle" :scroll="{ x: 1670 }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'stock'"><div class="stock-name">{{ record.stock.name }} <span>{{ record.stock.code }}</span></div><div class="industry"><a-tag color="blue">{{ record.stock.board }}</a-tag>{{ record.stock.industry }}</div></template>
                <template v-else-if="column.key === 'price'"><div :class="record.stock.price === null ? 'current-price network-fail' : 'current-price'">{{ formatPrice(record.stock.price) }}</div><div class="quote-meta"><a-badge :status="record.stock.quoteStatus === 'REALTIME' ? 'success' : 'error'" :text="quoteStatus(record.stock)" /> · {{ quoteTime(record.stock) }}</div></template>
                <template v-else-if="column.key === 'change'"><span :class="['change-value', record.stock.changePercent === null ? 'network-fail' : Number(record.stock.changePercent) >= 0 ? 'rise' : 'fall']">{{ formatChange(record.stock.changePercent) }}</span></template>
                <template v-else-if="column.key === 'score'"><a-tag :color="scoreColor(record.scores.finalScore)" class="score-tag">{{ record.scores.finalScore }}</a-tag><div class="score-breakdown"><span v-for="factor in scoreFactors(record.scores)" :key="factor.label" :class="factorClass(factor.label)">{{ factor.label }} {{ factor.value }}/{{ factor.max }}</span></div><div class="coverage-note">覆盖 {{ formatPercentValue(record.scores.coveragePercent) }}</div></template>
                <template v-else-if="column.key === 'rating'"><div class="recommendation-stars" :class="recommendationStarClass(record.scores.finalScore)" :aria-label="`${recommendationStars(record.scores.finalScore)} 星推荐`" :title="`${recommendationStars(record.scores.finalScore)} 星推荐`"><span v-for="index in 5" :key="index" :class="{ active: index <= recommendationStars(record.scores.finalScore) }">★</span></div><div class="recommendation-stars-score">{{ recommendationStars(record.scores.finalScore) }} 星</div></template>
                <template v-else-if="column.key === 'reason'"><div v-for="reason in record.recommendationReasons" :key="reason" :class="['recommend-reason', recommendationReasonClass(reason)]">{{ reason }}</div></template>
                <template v-else-if="column.key === 'companyAi'"><div v-if="record.companyAnalysis?.available" class="company-ai-analysis"><div><strong>主营</strong>{{ record.companyAnalysis.businessDescription }}</div><div><strong>前景</strong>{{ record.companyAnalysis.outlook }}</div><div class="company-ai-trend"><strong>趋势</strong><a-tag :color="companyTrendColor(record.companyAnalysis.futureTrend)">{{ record.companyAnalysis.futureTrend }}</a-tag><span>置信度 {{ formatPercentValue(Number(record.companyAnalysis.confidence) * 100) }}</span></div><div class="company-ai-risk"><strong>风险</strong>{{ record.companyAnalysis.risk }}</div></div><span v-else class="ai-unavailable">AI未返回，正在使用本地规则兜底</span></template>
                <template v-else-if="column.key === 'buy'">{{ record.hardFilter.passed ? `${formatNumber(record.scores.buyLow)} — ${formatNumber(record.scores.buyHigh)}` : '—' }}</template>
                <template v-else-if="column.key === 'event'"><a-button v-if="record.stock.majorEventType" type="link" size="small" :class="record.stock.majorEventType === '利空' ? 'event-link negative' : 'event-link positive'" @click="openEvent(record.stock)"><a-tag :color="eventColor(record.stock.majorEventType)">{{ record.stock.majorEventType }}</a-tag>{{ record.stock.majorEventTitle }}</a-button><span v-else class="muted">—</span></template>
                <template v-else-if="column.key === 'action'"><a-tag :color="record.action === '候选' ? 'blue' : record.action === '联网失败' ? 'red' : 'default'">{{ record.action }}</a-tag></template>
              </template>
            </a-table>
            <div class="mobile-list">
              <div v-for="record in recommendations" :key="record.stock.code" class="mobile-stock-card">
                <div class="mobile-stock-head">
                  <div>
                    <div class="stock-name">{{ record.stock.name }} <span>{{ record.stock.code }}</span></div>
                    <div class="industry"><a-tag color="blue">{{ record.stock.board }}</a-tag>{{ record.stock.industry }}</div>
                  </div>
                  <div class="mobile-score"><div class="recommendation-stars" :class="recommendationStarClass(record.scores.finalScore)" :aria-label="`${recommendationStars(record.scores.finalScore)} 星推荐`"><span v-for="index in 5" :key="index" :class="{ active: index <= recommendationStars(record.scores.finalScore) }">★</span></div><a-tag :color="scoreColor(record.scores.finalScore)" class="score-tag">{{ record.scores.finalScore }} 分</a-tag><div class="score-breakdown score-breakdown-mobile"><span v-for="factor in scoreFactors(record.scores)" :key="factor.label" :class="factorClass(factor.label)">{{ factor.label }} {{ factor.value }}/{{ factor.max }}</span></div></div>
                </div>
                <div class="mobile-metrics">
                  <div class="mobile-metric"><span>当前价</span><strong :class="record.stock.price === null ? 'network-fail' : 'current-price'">{{ formatPrice(record.stock.price) }}</strong><small>{{ quoteStatus(record.stock) }}</small></div>
                  <div class="mobile-metric"><span>涨跌</span><strong :class="['change-value', record.stock.changePercent === null ? 'network-fail' : Number(record.stock.changePercent) >= 0 ? 'rise' : 'fall']">{{ formatChange(record.stock.changePercent) }}</strong></div>
                  <div class="mobile-metric"><span>买入区间</span><strong class="price-green">{{ record.hardFilter.passed ? `${formatNumber(record.scores.buyLow)} — ${formatNumber(record.scores.buyHigh)}` : '—' }}</strong></div>
                </div>
                <div class="mobile-reasons"><div v-for="reason in record.recommendationReasons" :key="reason" :class="['recommend-reason', recommendationReasonClass(reason)]">{{ reason }}</div></div>
                <div v-if="record.companyAnalysis?.available" class="mobile-company-ai"><div><strong>AI公司分析</strong><a-tag :color="companyTrendColor(record.companyAnalysis.futureTrend)">{{ record.companyAnalysis.futureTrend }}</a-tag><span class="company-ai-confidence">置信度 {{ formatPercentValue(Number(record.companyAnalysis.confidence) * 100) }}</span></div><p><b>主营：</b>{{ record.companyAnalysis.businessDescription }}</p><p><b>前景：</b>{{ record.companyAnalysis.outlook }}</p><p class="company-ai-risk"><b>风险：</b>{{ record.companyAnalysis.risk }}</p></div><div v-else class="mobile-company-ai ai-unavailable">AI未返回，正在使用本地规则兜底</div>
                <div v-if="record.stock.majorEventType" class="mobile-event" @click="openEvent(record.stock)"><a-tag :color="eventColor(record.stock.majorEventType)">{{ record.stock.majorEventType }}</a-tag>{{ record.stock.majorEventTitle }}</div>
                <div class="mobile-card-actions"><a-tag :color="record.action === '候选' ? 'blue' : record.action === '联网失败' ? 'red' : 'default'">{{ record.action }}</a-tag><a-button type="primary" size="small" :disabled="record.action !== '候选'" @click="addOneStock(record)">加入股票池</a-button></div>
              </div>
              <a-empty v-if="!recommendations.length && !loading" description="当前价格区间暂无可推荐股票" />
            </div>
          </a-tab-pane>

          <a-tab-pane key="pool" tab="我的股票池">
            <div class="toolbar"><div><a-space wrap><a-button type="primary" @click="manualVisible = true">手工添加股票</a-button><a-button :loading="poolLoading" :disabled="poolLoading" @click="loadPool">快速重新分析</a-button><a-button @click="openHoldingOverview">持仓总览</a-button><a-button :loading="portfolioAnalysisLoading" @click="openPortfolioAnalysis">持仓分析</a-button></a-space><div class="toolbar-tip">共 {{ poolItems.length }} 只 · 调整持仓只维护已持有数量；买卖计划单独保存和分析，确认交易后才会改变实际持仓</div></div><a-tag color="orange">操作建议仅供研究</a-tag></div>
            <a-alert class="signal-explanation" type="info" show-icon message="操作建议和买入区间不是一回事" description="买入区间回答“价格在哪里更合适”；操作建议结合当前价、持仓、风险和仓位，回答“现在是否执行、买入还是卖出”。两者都不会自动下单。" />
    <a-card v-if="poolMarketContext" class="market-context-card" size="small"><a-space wrap><a-tag :color="poolMarketContext.regime === 'BULL' ? 'red' : poolMarketContext.regime === 'BEAR' ? 'green' : 'blue'">市场情绪 {{ poolMarketContext.sentimentAvailable ? poolMarketContext.sentimentScore : '不可用' }}</a-tag><a-tag>环境 {{ poolMarketContext.regime === 'BULL' ? '偏强' : poolMarketContext.regime === 'BEAR' ? '偏弱' : poolMarketContext.regime === 'NORMAL' ? '震荡' : '未知' }}</a-tag><span v-if="poolMarketContext.sentimentAvailable" class="market-context-text">上涨 {{ poolMarketContext.risingCount }} · 下跌 {{ poolMarketContext.fallingCount }} · 涨停 {{ poolMarketContext.limitUpCount }} · 跌停 {{ poolMarketContext.limitDownCount }} · 平均涨跌 {{ formatChange(poolMarketContext.averageChangePercent) }}</span><a-tag :color="poolMarketContext.newsAvailable ? 'purple' : 'default'">新闻热点：{{ poolMarketContext.newsAvailable ? `${poolMarketContext.positiveNewsCount} 利好 / ${poolMarketContext.negativeNewsCount} 利空` : '数据不可用，不参与评分' }}</a-tag></a-space><div class="market-context-hint">{{ poolMarketContext.highlights?.join('；') }}</div></a-card>
            <a-table class="desktop-table" :row-key="record => record.code" :columns="poolColumns" :data-source="poolItems" :loading="poolLoading" :pagination="{ pageSize: 8, showSizeChanger: false }" size="middle" :scroll="{ x: 1400 }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'stock'"><div class="stock-name">{{ record.analysis.stock.name }} <span>{{ record.code }}</span></div><div class="industry"><a-tag color="blue">{{ record.analysis.stock.board }}</a-tag>{{ record.analysis.stock.industry }} · {{ dataStatusText(record.analysis.stock) }} · 加入于 {{ record.addedAt }}</div><div v-if="record.analysis.stockContext?.sentimentAvailable" :class="['stock-context-line', record.analysis.stockContext.sentimentScore >= 60 ? 'sentiment-positive' : record.analysis.stockContext.sentimentScore <= 40 ? 'sentiment-negative' : 'sentiment-neutral']">个股情绪 {{ record.analysis.stockContext.sentimentScore }}/100 · {{ record.analysis.stockContext.sentimentLevel }}<span v-if="record.analysis.stockContext.newsAvailable"> · 新闻 {{ record.analysis.stockContext.positiveNewsCount }}利好/{{ record.analysis.stockContext.negativeNewsCount }}利空</span><span v-else> · 已实时查询，暂无该股关联报道</span></div></template>
                <template v-else-if="column.key === 'price'"><div :class="record.analysis.stock.price === null ? 'current-price network-fail' : 'current-price'">{{ formatPrice(record.analysis.stock.price) }}</div><div class="quote-meta"><a-badge :status="record.analysis.stock.quoteStatus === 'REALTIME' ? 'success' : 'error'" :text="quoteStatus(record.analysis.stock)" /> · {{ quoteTime(record.analysis.stock) }}</div></template>
                <template v-else-if="column.key === 'change'"><span :class="['change-value', record.analysis.stock.changePercent === null ? 'network-fail' : Number(record.analysis.stock.changePercent) >= 0 ? 'rise' : 'fall']">{{ formatChange(record.analysis.stock.changePercent) }}</span></template>
                <template v-else-if="column.key === 'position'"><div v-if="record.position.hasPosition" class="position-summary"><span :class="record.position.pnlAmount >= 0 ? 'rise' : 'fall'">当前 {{ record.position.action }} {{ formatPercent(record.position.pnlPercent / 100) }}</span><div class="quote-meta">{{ formatNumber(record.position.quantity) }} 股 · 市值 {{ formatMoney(record.position.marketValue) }}</div><div class="position-ratio" :class="positionRatioClass(record)">仓位 {{ formatPercentValue(record.position.positionPercent) }} / 上限 {{ formatPercentValue(record.position.maxPositionPercent) }}</div></div><div v-else class="quote-meta">当前持仓：未录入</div><div v-if="record.todayTrade?.hasTrade" class="today-trade">今日：{{ record.todayTrade.status }}</div><a-space size="small" wrap><a-button type="link" size="small" @click="openHistory(record)">持仓清单</a-button><a-button type="link" size="small" @click="openPosition(record)">调整持仓</a-button><a-button type="link" size="small" @click="openPlannedOrder(record)">买卖计划</a-button></a-space></template>
                <template v-else-if="column.key === 'score'"><a-tag :color="scoreColor(scoreOf(record))" class="score-tag">{{ scoreOf(record) }}</a-tag><div class="score-breakdown"><span v-for="factor in scoreFactors(record.analysis.scores)" :key="factor.label" :class="factorClass(factor.label)">{{ factor.label }} {{ factor.value }}/{{ factor.max }}</span></div><div class="coverage-note">覆盖 {{ formatPercentValue(record.analysis.scores.coveragePercent) }}</div></template>
                <template v-else-if="column.key === 'accuracy'"><div class="accuracy-stack"><div><a-tag :color="accuracyMetricColor(accuracyMetric(record.analysisAccuracy, 'prediction'))" :title="accuracyMetric(record.analysisAccuracy, 'prediction').method">预测 {{ accuracyMetricText(accuracyMetric(record.analysisAccuracy, 'prediction')) }}</a-tag><span class="accuracy-count">{{ accuracyMetricMeta(accuracyMetric(record.analysisAccuracy, 'prediction')) }}</span></div><div><a-tag :color="accuracyMetricColor(accuracyMetric(record.analysisAccuracy, 'operation'))" :title="accuracyMetric(record.analysisAccuracy, 'operation').method">执行 {{ accuracyMetricText(accuracyMetric(record.analysisAccuracy, 'operation')) }}</a-tag><span class="accuracy-count">{{ accuracyMetricMeta(accuracyMetric(record.analysisAccuracy, 'operation')) }}</span></div><div class="accuracy-time">{{ accuracyCalculatedAt(record.analysisAccuracy) }}</div></div></template>
                <template v-else-if="column.key === 'signal'"><a-tooltip title="买入区间看价格位置；操作建议结合现价、持仓和风险给出当前动作"><a-tag :color="operationAdviceColor(record)">{{ operationAdvice(record) }}</a-tag></a-tooltip><div v-if="tradeAction(record)" class="trade-action" :class="`trade-action-${tradeAction(record).type}`"><span class="trade-hand">{{ tradeAction(record).icon }}</span>{{ tradeAction(record).label }}</div></template>
                <template v-else-if="column.key === 'band'"><a-tag :color="record.tradePlan.band === '买入区' || record.tradePlan.band === '低吸区' ? 'green' : 'orange'">{{ record.tradePlan.band }}</a-tag></template>
                <template v-else-if="column.key === 'buy'"><span :class="buyAdviceClass(record)">{{ buyAdvice(record) }}</span></template>
                <template v-else-if="column.key === 'sell'"><div class="price-card price-take-profit">止盈 {{ formatNumber(record.tradePlan.takeProfit1) }} / {{ formatNumber(record.tradePlan.takeProfit2) }}</div><div class="price-card price-stop-loss">止损 {{ formatNumber(record.tradePlan.hardStop) }}</div></template>
                <template v-else-if="column.key === 'risk'"><a-tag :color="riskColor(record.tradePlan)">{{ riskText(record.tradePlan.riskLevel) }}</a-tag></template>
                <template v-else-if="column.key === 'event'"><a-button v-if="record.analysis.stock.majorEventType" type="link" size="small" :class="record.analysis.stock.majorEventType === '利空' ? 'event-link negative' : 'event-link positive'" @click="openEvent(record.analysis.stock)"><a-tag :color="eventColor(record.analysis.stock.majorEventType)">{{ record.analysis.stock.majorEventType }}</a-tag>{{ record.analysis.stock.majorEventTitle }}</a-button><span v-else class="muted">—</span></template>
                <template v-else-if="column.key === 'manage'"><a-space size="small" wrap><a-button type="link" size="small" @click="openPosition(record)">调整持仓</a-button><a-button type="link" size="small" @click="openPlannedOrder(record)">买卖计划</a-button><a-popconfirm title="确定从股票池移除？" ok-text="移除" cancel-text="取消" @confirm="removeStock(record.code)"><a-button type="link" danger>移除</a-button></a-popconfirm></a-space></template>
              </template>
              <template #expandedRowRender="{ record }"><div class="expanded-analysis"><a-row :gutter="24"><a-col :xs="24" :lg="8"><div class="analysis-title">持仓分析</div><a-alert v-if="record.position.hasPosition" :type="record.position.actionColor === 'red' ? 'error' : record.position.actionColor === 'green' ? 'success' : 'warning'" show-icon><template #message>{{ record.position.status }} · {{ record.position.action }}</template><template #description><div>成本 {{ formatMoney(record.position.buyPrice) }} · 数量 {{ formatNumber(record.position.quantity) }} 股</div><div>市值 {{ formatMoney(record.position.marketValue) }} · 盈亏 {{ formatMoney(record.position.pnlAmount) }}（{{ formatPercent(record.position.pnlPercent / 100) }}）</div><div v-for="hint in record.position.suggestions" :key="hint">· {{ hint }}</div></template></a-alert><a-alert v-else type="info" show-icon message="尚未维护持仓" description="点击录入持仓，填写买入价和买入数量。" /><div class="analysis-title">价格计划</div><a-descriptions :column="1" size="small"><a-descriptions-item label="买入区间"><span class="price-green">{{ formatNumber(record.tradePlan.buyLow) }} — {{ formatNumber(record.tradePlan.buyHigh) }}</span></a-descriptions-item><a-descriptions-item label="推测价"><span class="price-purple">{{ formatNumber(record.tradePlan.forecastPrice) }}</span></a-descriptions-item><a-descriptions-item label="第一止盈">{{ formatNumber(record.tradePlan.takeProfit1) }}</a-descriptions-item><a-descriptions-item label="第二止盈">{{ formatNumber(record.tradePlan.takeProfit2) }}</a-descriptions-item><a-descriptions-item label="硬止损"><span class="price-red">{{ formatNumber(record.tradePlan.hardStop) }}</span></a-descriptions-item><a-descriptions-item label="移动止损">{{ formatNumber(record.tradePlan.trailingStop) }}</a-descriptions-item></a-descriptions><div class="analysis-title t-title">做T建议</div><a-list size="small" :data-source="record.tradePlan.tSuggestions"><template #renderItem="{ item }"><a-list-item>{{ item }}</a-list-item></template></a-list></a-col><a-col :xs="24" :lg="8"><div class="analysis-title">波段提示</div><a-timeline><a-timeline-item v-for="hint in record.tradePlan.swingHints" :key="hint" color="blue">{{ hint }}</a-timeline-item></a-timeline></a-col><a-col :xs="24" :lg="8"><div class="analysis-title">风险提示</div><a-alert v-if="record.tradePlan.riskWarnings.length" type="warning" show-icon><template #message>风险等级：{{ riskText(record.tradePlan.riskLevel) }}</template><template #description><div v-for="warning in record.tradePlan.riskWarnings" :key="warning">· {{ warning }}</div></template></a-alert><a-alert v-else type="success" show-icon message="当前未发现明显风险项" /><a-alert v-if="record.analysis.stock.majorEventType" :type="record.analysis.stock.majorEventType === '利空' ? 'error' : 'success'" show-icon class="event-alert"><template #message>{{ record.analysis.stock.majorEventType }}：{{ record.analysis.stock.majorEventTitle }}</template><template #description>{{ record.analysis.stock.majorEventSummary }}</template></a-alert></a-col></a-row><div class="price-chart-panel"><div class="analysis-title">最近一个月价格走势 <span class="chart-subtitle">前复权收盘价 · 真实历史行情</span></div><template v-if="historyPoints(record).length >= 2"><div class="chart-legend"><span class="legend-item"><i class="legend-line actual"></i>收盘价</span><span class="legend-item"><i class="legend-line average"></i>近月均价 {{ formatNumber(chartAverage(record)) }}</span><span v-for="line in chartReferences(record)" :key="line.key" class="legend-item"><i class="legend-line" :style="{ borderTopColor: line.color, borderTopStyle: line.dash ? 'dashed' : 'solid' }"></i>{{ line.label }} {{ formatNumber(line.value) }}</span></div><div class="price-chart-wrap"><svg class="price-chart" viewBox="0 0 720 280" role="img" aria-label="最近一个月价格走势"><line v-for="(value, index) in chartGridValues(record)" :key="`grid-${index}`" x1="48" x2="698" :y1="chartY(record, value)" :y2="chartY(record, value)" class="chart-grid" /><text v-for="(value, index) in chartGridValues(record)" :key="`grid-label-${index}`" x="42" :y="chartY(record, value) + 4" text-anchor="end" class="chart-axis-label">{{ formatNumber(value) }}</text><line v-if="chartAverage(record) !== null" x1="48" x2="698" :y1="chartY(record, chartAverage(record))" :y2="chartY(record, chartAverage(record))" stroke="#8c8c8c" stroke-dasharray="3 3" class="chart-reference" /><line v-for="line in chartReferences(record)" :key="line.key" x1="48" x2="698" :y1="chartY(record, line.value)" :y2="chartY(record, line.value)" :stroke="line.color" :stroke-dasharray="line.dash" class="chart-reference" /><path :d="chartPath(record)" class="chart-price-line" /><circle v-for="(point, index) in historyPoints(record)" :key="`${point.date}-${index}`" :cx="chartX(record, index)" :cy="chartY(record, point.price)" r="2.6" class="chart-point" /><text v-for="index in chartDateIndexes(record)" :key="`date-${index}`" :x="chartX(record, index)" y="263" text-anchor="middle" class="chart-axis-label">{{ chartDate(record, index) }}</text></svg></div></template><a-alert v-else type="warning" show-icon message="最近一个月历史行情数据不足，暂不绘制价格曲线" description="系统不会使用虚拟价格；行情恢复后重新分析股票池即可补齐图表。" /></div><div class="price-ladder-panel"><div class="analysis-title">分批价格建议 <span class="chart-subtitle">基于当前技术参考区间，不代表必然成交</span></div><div :class="[`current-price-card`, currentPriceClass(record)]"><div><span>当前价</span><strong>{{ formatPrice(record.analysis.stock.price) }}</strong></div><em>{{ currentPriceHint(record) }}</em></div><div class="price-ladder-grid"><div class="ladder-card ladder-buy"><div class="ladder-title">5档建议买入价</div><div v-for="(price, index) in buyPriceLevels(record)" :key="`buy-level-${index}`" class="ladder-row"><span>第{{ index + 1 }}档</span><strong>{{ formatNumber(price) }} <small class="ladder-quantity">建议 {{ suggestedQuantityText(buySuggestedQuantity(record, price)) }}</small></strong><small>{{ index === 0 ? '下一承接' : index === 4 ? '参考上沿' : '分批观察' }}</small></div><div v-if="!buyPriceLevels(record).length" class="ladder-empty">缺少真实技术数据</div></div><div class="ladder-card ladder-sell"><div class="ladder-title">5档建议卖出价</div><div v-for="(price, index) in sellPriceLevels(record)" :key="`sell-level-${index}`" class="ladder-row"><span>第{{ index + 1 }}档</span><strong>{{ formatNumber(price) }} <small class="ladder-quantity">建议 {{ suggestedQuantityText(sellSuggestedQuantity(record, index)) }}</small></strong><small>{{ index === 0 ? '第一目标' : index === 4 ? '第二目标' : '分批止盈' }}</small></div><div v-if="!sellPriceLevels(record).length" class="ladder-empty">缺少止盈参考</div></div></div><div class="profit-forecast"><div class="ladder-title">当前持仓盈利预计</div><template v-if="holdingProfitForecast(record)"><div class="profit-summary"><span>成本 {{ formatNumber(holdingProfitForecast(record).cost) }} · {{ formatNumber(holdingProfitForecast(record).quantity) }} 股</span><strong :class="holdingProfitForecast(record).currentProfit >= 0 ? 'rise' : 'fall'">当前 {{ holdingProfitForecast(record).currentProfit >= 0 ? '浮盈' : '浮亏' }} {{ formatMoney(Math.abs(holdingProfitForecast(record).currentProfit)) }}（{{ formatPercentValue(holdingProfitForecast(record).currentProfitPercent) }}）</strong></div><div class="profit-levels"><div v-for="level in holdingProfitForecast(record).levels" :key="`profit-${level.price}`" class="profit-level"><span>卖出 {{ formatNumber(level.price) }}</span><strong :class="level.profit >= 0 ? 'rise' : 'fall'">预计 {{ level.profit >= 0 ? '盈利' : '亏损' }} {{ formatMoney(Math.abs(level.profit)) }}（{{ formatPercentValue(level.percent) }}）</strong></div></div></template><div v-else class="ladder-empty">未录入历史持仓，暂不计算持仓盈利预计</div></div></div></div></template>
            </a-table>
            <div class="mobile-list">
              <div v-for="record in poolItems" :key="record.code" class="mobile-stock-card">
                <div class="mobile-stock-head">
                  <div>
                    <div class="stock-name">{{ record.analysis.stock.name }} <span>{{ record.code }}</span></div>
                    <div class="industry"><a-tag color="blue">{{ record.analysis.stock.board }}</a-tag>{{ record.analysis.stock.industry }}</div>
                    <div v-if="record.analysis.stockContext?.sentimentAvailable" :class="['stock-context-line', record.analysis.stockContext.sentimentScore >= 60 ? 'sentiment-positive' : record.analysis.stockContext.sentimentScore <= 40 ? 'sentiment-negative' : 'sentiment-neutral']">个股情绪 {{ record.analysis.stockContext.sentimentScore }}/100 · {{ record.analysis.stockContext.sentimentLevel }}<span v-if="record.analysis.stockContext.newsAvailable"> · 新闻 {{ record.analysis.stockContext.positiveNewsCount }}利好/{{ record.analysis.stockContext.negativeNewsCount }}利空</span><span v-else> · 已实时查询，暂无该股关联报道</span></div>
                  </div>
                  <div class="mobile-score"><a-tag :color="scoreColor(scoreOf(record))" class="score-tag">{{ scoreOf(record) }} 分</a-tag><div class="score-breakdown score-breakdown-mobile"><span v-for="factor in scoreFactors(record.analysis.scores)" :key="factor.label" :class="factorClass(factor.label)">{{ factor.label }} {{ factor.value }}/{{ factor.max }}</span></div></div>
                </div>
                <div class="mobile-metrics">
                  <div class="mobile-metric"><span>当前价</span><strong :class="record.analysis.stock.price === null ? 'network-fail' : 'current-price'">{{ formatPrice(record.analysis.stock.price) }}</strong><small>{{ quoteStatus(record.analysis.stock) }} · {{ quoteTime(record.analysis.stock) }}</small></div>
                  <div class="mobile-metric"><span>涨跌</span><strong :class="['change-value', record.analysis.stock.changePercent === null ? 'network-fail' : Number(record.analysis.stock.changePercent) >= 0 ? 'rise' : 'fall']">{{ formatChange(record.analysis.stock.changePercent) }}</strong></div>
                  <div class="mobile-metric"><span>仓位</span><strong :class="['position-ratio', positionRatioClass(record)]">{{ record.position.hasPosition ? formatPercentValue(record.position.positionPercent) : '未设置' }}</strong></div>
                  <div class="mobile-metric"><span>准确率</span><strong class="current-price">预测 {{ accuracyMetricText(accuracyMetric(record.analysisAccuracy, 'prediction')) }}</strong><small>执行 {{ accuracyMetricText(accuracyMetric(record.analysisAccuracy, 'operation')) }} · {{ accuracyCalculatedAt(record.analysisAccuracy) }}</small></div>
                </div>
                <div class="mobile-plan-grid">
                  <div><span>买入参考</span><strong :class="buyAdviceClass(record)">{{ buyAdvice(record) }}</strong></div>
                  <div><span>当前波段</span><a-tag :color="record.tradePlan.band === '买入区' || record.tradePlan.band === '低吸区' ? 'green' : 'orange'">{{ record.tradePlan.band }}</a-tag></div>
                  <div><span>卖出参考</span><div class="mobile-sell-reference"><strong class="price-card price-take-profit">止盈 {{ formatNumber(record.tradePlan.takeProfit1) }}</strong><strong class="price-card price-stop-loss">止损 {{ formatNumber(record.tradePlan.hardStop) }}</strong></div></div>
                </div>
                <div class="mobile-signal"><a-tooltip title="买入区间看价格位置；操作建议结合现价、持仓和风险给出当前动作"><a-tag :color="operationAdviceColor(record)">{{ operationAdvice(record) }}</a-tag></a-tooltip><span class="risk-inline"><a-tag :color="riskColor(record.tradePlan)">{{ riskText(record.tradePlan.riskLevel) }}</a-tag></span><div v-if="tradeAction(record)" class="trade-action" :class="`trade-action-${tradeAction(record).type}`"><span class="trade-hand">{{ tradeAction(record).icon }}</span>{{ tradeAction(record).label }}</div></div>
                <div v-if="record.position.hasPosition" class="mobile-holding">历史持仓：{{ formatNumber(record.position.quantity) }} 股 · 成本 {{ formatMoney(record.position.buyPrice) }} · {{ record.position.action }} <span :class="record.position.pnlAmount >= 0 ? 'rise' : 'fall'">{{ formatMoney(record.position.pnlAmount) }}</span></div>
                <div v-if="record.todayTrade?.hasTrade" class="today-trade">今日交易：{{ record.todayTrade.status }} · 成交 {{ formatMoney(record.todayTrade.executedPrice) }}</div>
                <div v-if="record.analysis.stock.majorEventType" class="mobile-event" @click="openEvent(record.analysis.stock)"><a-tag :color="eventColor(record.analysis.stock.majorEventType)">{{ record.analysis.stock.majorEventType }}</a-tag>{{ record.analysis.stock.majorEventTitle }}</div>
                <div class="mobile-card-actions"><a-button size="small" @click="openHistory(record)">持仓清单</a-button><a-button size="small" type="primary" ghost @click="openPosition(record)">调整持仓</a-button><a-button size="small" @click="openPlannedOrder(record)">买卖计划</a-button><a-popconfirm title="确定从股票池移除？" ok-text="移除" cancel-text="取消" @confirm="removeStock(record.code)"><a-button size="small" danger>移除</a-button></a-popconfirm></div>
              </div>
              <a-empty v-if="!poolItems.length && !poolLoading" description="还没有股票入池，请从推荐中心勾选或手工添加" />
            </div>
            <a-empty v-if="!poolItems.length && !poolLoading" description="还没有股票入池，请从推荐中心勾选或手工添加" />
          </a-tab-pane>
        </a-tabs>
      </a-card>

      <div class="score-structure-trigger"><a-button type="link" @click="openScoreStructure">查看评分结构与交易纪律</a-button></div>
      <a-row :gutter="16" class="bottom-row"><a-col :xs="24" :lg="14"><a-card title="评分结构" size="small"><a-space wrap><a-tag v-for="factor in scoreStructure" :key="factor.label" :color="factor.color">{{ factor.label }} {{ factor.score }}</a-tag></a-space><div class="factor-explanations"><div v-for="factor in scoreStructure" :key="`${factor.label}-summary`" class="factor-explanation"><div><strong>{{ factor.label }} · {{ factor.score }}分</strong><span>{{ factor.note }}</span></div><p>{{ factor.description }}</p></div></div><div class="footnote">最终评分会先按当前模型权重折算到 100 分，再扣除风险惩罚。AI每小时只调整经过安全校验的权重，不直接输出买卖决定。</div></a-card></a-col><a-col :xs="24" :lg="10"><a-card title="交易纪律" size="small"><a-alert type="info" show-icon message="先入池，后分析；先看区间，后做决定。" description="价格提示只基于联网获取的真实行情和策略参数；行情失败时不生成价格，恢复后自动重新分析。" /></a-card></a-col></a-row>
      <a-modal v-model:open="modelDetailVisible" :title="selectedModelDisplay ? selectedModelDisplay.name : '模型详情'" :footer="null" width="760px">
        <template v-if="selectedModelDisplay">
          <div class="model-detail-summary">
            <div>
              <a-tag :color="selectedModelDisplay.color">{{ selectedModelDisplay.type }}</a-tag>
              <a-tag :color="modelStatusColor(selectedModel)">{{ modelStatus(selectedModel) }}</a-tag>
            </div>
            <div class="model-detail-score"><span>{{ selectedModelDisplay.key === 'stock-score' ? '模型可信度' : '真实数据覆盖' }}</span><strong>{{ modelDisplayScore(selectedModel) }}</strong><em>/100</em></div>
          </div>
          <a-progress :percent="selectedModelDisplay.score" stroke-color="#2563eb" />
          <p class="model-detail-description">{{ selectedModelDisplay.summary }}</p>
          <div class="model-visual" aria-label="模型公式和工作流程">
            <div class="model-visual-heading"><span>工作原理</span><small>FORMULA / FLOW</small></div>
            <div class="model-equation">{{ selectedModelDisplay.formula }}</div>
            <div class="model-flow" role="list">
              <template v-for="(step, index) in selectedModelDisplay.steps" :key="step">
                <div class="model-flow-node" role="listitem"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ step }}</div>
                <div v-if="index < selectedModelDisplay.steps.length - 1" class="model-flow-arrow" aria-hidden="true">→</div>
              </template>
            </div>
          </div>
          <a-descriptions bordered :column="1" size="small" class="model-detail-descriptions">
            <a-descriptions-item label="真实版本">{{ modelDisplayName(selectedModel) }} · {{ modelStatus(selectedModel) }}</a-descriptions-item>
            <a-descriptions-item label="数据库审计">{{ modelEvidence(selectedModel) }} · 最近生成 {{ modelGeneratedAt(selectedModel) }}</a-descriptions-item>
            <a-descriptions-item label="输入数据">{{ selectedModelDisplay.inputs }}</a-descriptions-item>
            <a-descriptions-item label="输出结果">{{ selectedModelDisplay.outputs }}</a-descriptions-item>
            <a-descriptions-item label="运行方式">{{ selectedModelDisplay.method }}</a-descriptions-item>
            <a-descriptions-item label="评分口径">覆盖能力、可解释性、数据完整度、稳定性综合评估；不是历史收益率。</a-descriptions-item>
          </a-descriptions>
          <div class="model-detail-actions"><a-button @click="modelDetailVisible = false">关闭</a-button><a-button type="primary" :loading="modelRegeneratingKey === selectedModel.key" :disabled="Boolean(modelRegeneratingKey || modelDataGeneratingKey)" @click="regenerateModel(selectedModel)">重新生成模型</a-button><a-button type="primary" ghost :loading="modelDataGeneratingKey === selectedModel.key" :disabled="Boolean(modelRegeneratingKey || modelDataGeneratingKey)" @click="generateModelData(selectedModel)">生成数据</a-button><a-button @click="handleModelAction(selectedModel)">{{ selectedModel.action }} →</a-button></div>
        </template>
      </a-modal>
      <a-modal v-model:open="scoreStructureVisible" title="评分结构与交易纪律" :footer="null" width="1060px">
        <a-row :gutter="16" class="score-structure-popup">
          <a-col :xs="24" :lg="14">
            <a-card title="评分结构" size="small">
              <a-alert :type="Number(currentScoringModel.confidence || 0) >= 0.8 ? 'success' : 'warning'" show-icon :message="`当前生效模型：${scoringModelCardName}${Number(currentScoringModel.confidence || 0) < 0.8 ? ' · 可信度未达80%，等待重生成' : ''}`" :description="`最近调整：${currentScoringModel.generatedAt ? String(currentScoringModel.generatedAt).replace('T', ' ').slice(0, 19) : '尚未调整'} · ${currentScoringModel.aiAdjusted ? 'AI结合持仓盈亏、准确率和市场环境并通过安全校验' : '系统默认模型'} · 置信度 ${formatPercentValue(Number(currentScoringModel.confidence || 0) * 100)}`" />
              <a-space wrap><a-tag v-for="factor in scoreStructure" :key="factor.label" :color="factor.color">{{ factor.label }} {{ factor.score }}</a-tag></a-space>
              <div class="model-adjustment-summary"><strong>本轮模型调整摘要</strong><span>{{ scoringModel?.adjustmentSummary || '等待每小时AI模型治理任务' }}</span><div v-if="scoringModel?.credibilityBasis">· 可信度依据：{{ scoringModel.credibilityBasis }}</div><div v-if="scoringModel?.adjustmentReasons?.length" v-for="reason in scoringModel.adjustmentReasons" :key="reason">· {{ reason }}</div></div>
              <div class="factor-explanations"><div v-for="factor in scoreStructure" :key="`${factor.label}-detail`" class="factor-explanation"><div><strong>{{ factor.label }} · {{ factor.score }}分</strong><span>{{ factor.note }}</span></div><p>{{ factor.description }}</p></div></div>
              <div class="footnote">模型调整只改变经过边界校验的评分权重；行情、技术指标、仓位和交易规则仍由系统本地计算，AI不直接决定买卖。</div>
            </a-card>
          </a-col>
          <a-col :xs="24" :lg="10"><a-card title="交易纪律" size="small"><a-alert type="info" show-icon message="先入池，后分析；先看区间，后做决定。" description="价格提示只基于联网获取的真实行情和策略参数；行情失败时不生成价格，恢复后自动重新分析。" /></a-card></a-col>
        </a-row>
      </a-modal>
    </a-layout-content>

    <a-modal v-model:open="aiVisible" title="维护 AI 接入" ok-text="保存配置" cancel-text="取消" :confirm-loading="aiLoading" @ok="saveAiConfig" width="600px"><a-form layout="vertical"><a-alert type="info" show-icon message="AI 仅用于新闻、公告和财报摘要，不直接决定 BUY / SELL。" description="API Key 只保存到后端配置表，页面不会回显完整密钥；API Key 留空表示保持现有密钥不变。" /><a-form-item label="供应商" required><a-input v-model:value="aiForm.provider" placeholder="例如：DeepSeek" /></a-form-item><a-form-item label="模型" required><a-input v-model:value="aiForm.model" placeholder="例如：deepseek-v4-pro" /></a-form-item><a-form-item label="OpenAI 兼容接口地址" required><a-input v-model:value="aiForm.baseUrl" placeholder="例如：https://api.deepseek.com" /></a-form-item><a-form-item label="API Key"><a-input-password v-model:value="aiForm.apiKey" placeholder="留空保持现有 API Key 不变" /></a-form-item><a-form-item label="启用 AI 分析"><a-switch v-model:checked="aiForm.enabled" /><span class="form-inline-hint">{{ aiForm.enabled ? '启用后可供新闻热点分析使用' : '关闭后不调用 AI' }}</span></a-form-item><a-alert v-if="aiConfig" :type="aiConfig.apiKeyConfigured ? 'success' : 'warning'" show-icon :message="aiConfig.apiKeyConfigured ? `当前密钥：${aiConfig.apiKeyMasked}` : '当前尚未配置 API Key'" description="当前页面只展示脱敏后的密钥状态。" /></a-form></a-modal>
    <a-modal v-model:open="marketDataSourceVisible" title="维护行情源" :footer="null" width="1240px"><a-alert type="info" show-icon message="系统按优先级依次调用启用的行情源，单个源失败会按重试次数重试，仍失败后自动切换下一个源；所有源失败才显示联网失败。" description="适配器决定响应解析方式，接口地址、超时、重试、优先级均可维护。保存后对下一次实时行情和推荐扫描生效。" /><a-table class="source-config-table" :columns="marketDataSourceColumns" :data-source="marketDataSources" row-key="sourceKey" :pagination="false" :scroll="{ x: 1800 }" size="small"><template #bodyCell="{ column, record }"><template v-if="column.key === 'enabled'"><a-switch v-model:checked="record.enabled" size="small" /></template><template v-else-if="column.key === 'name'"><a-input v-model:value="record.name" size="small" /></template><template v-else-if="column.key === 'purpose'"><a-tag :color="record.purpose === 'UNIVERSE' ? 'blue' : 'green'">{{ record.purpose === 'UNIVERSE' ? '全市场' : '实时行情' }}</a-tag></template><template v-else-if="column.key === 'adapter'"><a-select v-model:value="record.adapter" size="small" style="width:105px"><a-select-option value="EASTMONEY">东方财富</a-select-option><a-select-option value="TENCENT">腾讯</a-select-option><a-select-option value="SINA">新浪</a-select-option></a-select></template><template v-else-if="column.key === 'priority'"><a-input-number v-model:value="record.priority" :min="1" :max="999" size="small" style="width:78px" /></template><template v-else-if="column.key === 'endpoint'"><a-input v-model:value="record.endpoint" size="small" /></template><template v-else-if="column.key === 'timeoutSeconds'"><a-input-number v-model:value="record.timeoutSeconds" :min="2" :max="60" size="small" style="width:82px" /></template><template v-else-if="column.key === 'retryCount'"><a-input-number v-model:value="record.retryCount" :min="0" :max="5" size="small" style="width:82px" /></template><template v-else-if="column.key === 'userAgent'"><a-input v-model:value="record.userAgent" size="small" /></template><template v-else-if="column.key === 'referer'"><a-input v-model:value="record.referer" size="small" /></template></template></a-table><div class="source-config-actions"><a-button @click="marketDataSourceVisible = false">取消</a-button><a-button type="primary" :loading="marketDataSourceLoading" @click="saveMarketDataSources">保存行情源配置</a-button></div></a-modal>
    <a-modal v-model:open="accountVisible" title="维护账户总资产" ok-text="保存并重新分析" cancel-text="取消" :confirm-loading="accountLoading" @ok="saveAccountAssets" width="520px"><a-form layout="vertical"><a-alert type="info" show-icon message="用于计算持仓比例和计划买入/卖出后的预计仓位" description="请输入当前账户总资产，包含现金和持仓市值。系统将按策略配置中的单股仓位上限提示买入后的仓位，不会自动下单。" /><a-form-item label="账户总资产" required><a-input-number v-model:value="accountForm.totalAssets" :min="0.001" :precision="3" :step="1000" addon-after="元" style="width:100%" /></a-form-item><a-alert v-if="accountAssets?.updatedAt" type="success" show-icon :message="`最近保存：${String(accountAssets.updatedAt).replace('T', ' ').slice(0, 19)}`" description="修改后会重新计算股票池中所有历史持仓和计划操作的仓位比例。" /></a-form></a-modal>
    <a-modal v-model:open="manualVisible" title="手工加入股票池" ok-text="加入股票池" cancel-text="取消" :confirm-loading="manualLoading" @ok="addManual"><a-form layout="vertical"><a-form-item label="股票代码" required><a-input v-model:value="form.code" placeholder="例如：600519" /></a-form-item><a-form-item label="股票名称"><a-input v-model:value="form.name" placeholder="可选，行情源接入后自动覆盖" /></a-form-item><a-form-item label="行业"><a-input v-model:value="form.industry" placeholder="可选" /></a-form-item></a-form><a-alert type="warning" show-icon message="手工添加后才会进入股票池" description="如果联网获取不到该股票价格，系统会显示联网失败，不会使用虚拟价格。" /></a-modal>
    <a-modal v-model:open="historyVisible" :title="selectedHistory ? `${selectedHistory.analysis.stock.name}（${selectedHistory.code}）持仓清单` : '持仓清单'" :footer="null" width="820px">
      <a-spin :spinning="historyLoading">
        <a-empty v-if="!historyLoading && !selectedHistory" description="暂无历史记录" />
        <template v-else-if="selectedHistory">
          <a-alert v-if="!selectedHistory.position?.hasPosition" type="info" show-icon message="当前没有持仓" description="下面仍会保留已确认的买入、卖出变化；录入持仓后会显示当前总股数和市值。" />
          <a-row v-else :gutter="12" class="holding-history-summary">
            <a-col :xs="12" :sm="6"><a-statistic title="当前总股数" :value="selectedHistory.position.quantity" suffix="股" /></a-col>
            <a-col :xs="12" :sm="6"><a-statistic title="当前金额" :value="selectedHistory.position.marketValue" :precision="3" suffix="元" /></a-col>
            <a-col :xs="12" :sm="6"><a-statistic title="持仓成本" :value="Number(selectedHistory.position.buyPrice || 0) * Number(selectedHistory.position.quantity || 0)" :precision="3" suffix="元" /></a-col>
            <a-col :xs="12" :sm="6"><a-statistic title="浮动盈亏" :value="selectedHistory.position.pnlAmount" :precision="3" suffix="元" :value-style="{ color: Number(selectedHistory.position.pnlAmount || 0) >= 0 ? '#cf1322' : '#16803c' }" /></a-col>
          </a-row>
          <div class="holding-history-caption">持仓清单记录当前数据库持仓快照，以及已经确认的买入 / 卖出变化；“当前金额”按最新真实行情计算。</div>
          <a-timeline class="history-timeline">
          <a-timeline-item v-for="event in historyTimeline(selectedHistory)" :key="event.key" :color="event.color">
            <div class="history-event-date">{{ event.date }}</div>
            <div class="history-event-title">{{ event.title }}</div>
            <div class="history-event-description">{{ event.description }}</div>
          </a-timeline-item>
          </a-timeline>
        </template>
      </a-spin>
    </a-modal>
    <a-modal v-model:open="positionVisible" :title="selectedPosition ? `调整持仓：${selectedPosition.analysis.stock.name}（${selectedPosition.code}）` : '调整持仓'" :footer="null" width="760px">
      <a-spin :spinning="positionRecordLoading">
        <a-alert v-if="selectedPosition" type="info" show-icon :message="`当前价：${formatPrice(selectedPosition.analysis.stock.price)} · 涨跌：${formatChange(selectedPosition.analysis.stock.changePercent)}`" description="这里维护已经发生的实际持仓，不是买卖计划。保存后会写入 simulated_position，并参与持仓市值、盈亏、仓位和卖出数量校验。" />
        <a-card class="position-record-card" size="small" title="数据库持仓记录">
          <template #extra><a-tag v-if="positionRecord?.found" :color="positionRecord.source === 'DATABASE' ? 'green' : 'orange'">{{ positionRecord.source === 'DATABASE' ? '已从数据库读取' : '内存兜底' }}</a-tag></template>
          <a-empty v-if="!positionRecord?.found" description="数据库暂无这只股票的持仓记录" />
          <a-descriptions v-else bordered size="small" :column="2">
            <a-descriptions-item label="总股数">{{ formatNumber(positionRecord.quantity) }} 股</a-descriptions-item>
            <a-descriptions-item label="可卖股数">{{ formatNumber(positionRecord.availableQuantity) }} 股</a-descriptions-item>
            <a-descriptions-item label="平均成本">{{ formatMoney(positionRecord.avgCost) }} 元</a-descriptions-item>
            <a-descriptions-item label="当前金额">{{ formatMoney(selectedPosition?.position?.marketValue) }} 元</a-descriptions-item>
            <a-descriptions-item label="建仓日期">{{ positionRecord.openedAt || '—' }}</a-descriptions-item>
            <a-descriptions-item label="最近更新">{{ positionRecord.updatedAt ? formatDateTime(positionRecord.updatedAt) : '—' }}</a-descriptions-item>
          </a-descriptions>
        </a-card>
        <a-form layout="vertical" class="position-adjust-form">
          <a-form-item label="买入均价" required><a-input-number v-model:value="positionForm.buyPrice" :min="0.001" :precision="3" :step="0.001" addon-after="元" style="width:100%" /></a-form-item>
          <a-form-item label="持仓总股数" required><a-input-number v-model:value="positionForm.quantity" :min="1" :precision="0" :step="100" addon-after="股" style="width:100%" /></a-form-item>
          <a-form-item label="建仓日期"><a-input v-model:value="positionForm.openedAt" type="date" /></a-form-item>
          <a-alert v-if="selectedPosition?.position?.hasPosition" type="warning" show-icon message="保存会覆盖当前数据库持仓记录" description="如需记录买入 / 卖出变化，请在“买卖计划”中确认交易；确认后的数量变化会自动合并到持仓。" />
          <a-space wrap class="position-modal-actions"><a-button type="primary" :loading="positionLoading" @click="savePosition">保存持仓并分析</a-button><a-button v-if="selectedPosition?.position?.hasPosition" danger :loading="positionLoading" @click="clearPosition">清除持仓</a-button><a-button @click="positionVisible = false">关闭</a-button></a-space>
        </a-form>
      </a-spin>
    </a-modal>
    <a-modal v-model:open="plannedVisible" :title="selectedPlanned ? `买卖计划：${selectedPlanned.analysis.stock.name}（${selectedPlanned.code}）` : '买卖计划'" :footer="null" :confirm-loading="plannedLoading" width="700px"><a-form layout="vertical"><a-alert v-if="selectedPlanned" type="info" show-icon :message="`当前真实价：${formatPrice(selectedPlanned.analysis.stock.price)} · 涨跌：${formatChange(selectedPlanned.analysis.stock.changePercent)}`" description="买入区间是系统给出的价格参考；买卖计划是你准备执行的方向、价格和数量。计划会单独保存到数据库，确认今日交易后才会写入成交记录并改变持仓。" /><a-form-item label="操作方向" required><a-radio-group v-model:value="plannedForm.side" button-style="solid"><a-radio-button value="BUY">买入</a-radio-button><a-radio-button value="SELL">卖出</a-radio-button></a-radio-group></a-form-item><a-form-item :label="`准备${plannedForm.side === 'SELL' ? '卖出' : '买入'}价格`" required><a-input-number v-model:value="plannedForm.plannedPrice" :min="0.001" :precision="3" :step="0.001" addon-after="元" style="width:100%" /></a-form-item><a-form-item :label="`准备${plannedForm.side === 'SELL' ? '卖出' : '买入'}数量`" required><a-input-number v-model:value="plannedForm.quantity" :min="1" :precision="0" :step="100" addon-after="股" style="width:100%" /></a-form-item><a-form-item label="计划日期"><a-input v-model:value="plannedForm.tradeDate" type="date" /></a-form-item><a-alert v-if="selectedPlanned?.plannedOrder?.hasPlan" :type="planDecisionAlertType(selectedPlanned.plannedOrder.decision)" show-icon class="plan-decision-alert"><template #message>综合结论：{{ selectedPlanned.plannedOrder.decision || '无法判断' }} · 预计交易成功率 {{ selectedPlanned.plannedOrder.successProbability === null || selectedPlanned.plannedOrder.successProbability === undefined ? '暂不可估算' : `${formatPercentValue(selectedPlanned.plannedOrder.successProbability)}` }}</template><template #description><div>{{ selectedPlanned.plannedOrder.decisionReason || '缺少综合分析结论' }}</div><div class="probability-note">{{ selectedPlanned.plannedOrder.probabilityReason || '预计交易成功率由每小时AI模型计算，非历史统计胜率' }}</div><div class="probability-note">{{ planDecisionHint(selectedPlanned.plannedOrder.decision) }}</div></template></a-alert><a-alert v-if="selectedPlanned?.plannedOrder?.hasPlan" :type="selectedPlanned.plannedOrder.actionColor === 'red' ? 'error' : selectedPlanned.plannedOrder.actionColor === 'green' ? 'success' : 'warning'" show-icon><template #message>{{ selectedPlanned.plannedOrder.side === 'SELL' ? '卖出' : '买入' }} · {{ selectedPlanned.plannedOrder.status }} · {{ selectedPlanned.plannedOrder.action }} · 当前价差 {{ formatGap(selectedPlanned.plannedOrder.gapPercent) }}</template><template #description><div v-for="hint in selectedPlanned.plannedOrder.suggestions" :key="hint">· {{ hint }}</div><div v-for="warning in selectedPlanned.plannedOrder.riskWarnings" :key="warning" class="fall">风险：{{ warning }}</div></template></a-alert><a-alert v-if="selectedPlanned?.todayTrade?.hasTrade" type="success" show-icon message="今日成果已记录" :description="`${selectedPlanned.todayTrade.side === 'SELL' ? '卖出' : '买入'} · 成交 ${formatMoney(selectedPlanned.todayTrade.executedPrice)} · ${selectedPlanned.todayTrade.status}`" /><a-space wrap><a-button type="primary" :loading="plannedLoading" @click="savePlannedOrder">保存计划并分析</a-button><a-button @click="analyzePlannedOrder">仅分析不保存</a-button><a-button type="primary" ghost :loading="plannedLoading" :disabled="!canConfirmPlannedOrder(selectedPlanned?.plannedOrder, selectedPlanned?.todayTrade)" @click="confirmPlannedOrder">确认今日交易</a-button><a-button v-if="selectedPlanned?.plannedOrder?.hasPlan" danger :loading="plannedLoading" @click="clearPlannedOrder">清除计划</a-button><a-button @click="plannedVisible = false">关闭</a-button></a-space></a-form></a-modal>
    <a-modal v-model:open="eventVisible" title="重大事件详情" :footer="null" width="620px"><a-descriptions v-if="selectedEvent" :column="1" bordered><a-descriptions-item label="股票">{{ selectedEvent.name }}（{{ selectedEvent.code }}）</a-descriptions-item><a-descriptions-item label="事件类型"><a-tag :color="eventColor(selectedEvent.majorEventType)">{{ selectedEvent.majorEventType }}</a-tag></a-descriptions-item><a-descriptions-item label="发生时间">{{ eventTime(selectedEvent.majorEventTime) }}</a-descriptions-item><a-descriptions-item label="事件标题">{{ selectedEvent.majorEventTitle }}</a-descriptions-item><a-descriptions-item label="详细消息">{{ selectedEvent.majorEventSummary || '暂无详细消息' }}</a-descriptions-item><a-descriptions-item v-if="selectedEvent.majorEventUrl" label="原文链接"><a :href="selectedEvent.majorEventUrl" target="_blank" rel="noopener noreferrer">打开原文</a></a-descriptions-item></a-descriptions></a-modal>
      <a-modal v-model:open="portfolioAnalysisVisible" title="持仓分析" :footer="null" width="820px" @cancel="stopPortfolioAnalysisPolling">
      <a-spin :spinning="portfolioAnalysisLoading" tip="AI正在结合大盘和全部持仓进行复盘">
        <template v-if="portfolioAnalysis?.available">
          <a-alert type="info" show-icon :message="portfolioAnalysis.marketOverview || '暂无大盘概况'" :description="portfolioAnalysis.portfolioOverview || '暂无组合概况'" />
          <div class="portfolio-analysis-meta">分析时间：{{ String(portfolioAnalysis.analyzedAt || '').replace('T', ' ').slice(0, 19) }} · AI置信度：{{ formatPercentValue(Number(portfolioAnalysis.confidence || 0) * 100) }}<span v-if="portfolioAnalysis.message && portfolioAnalysis.message !== 'AI组合分析完成'"> · {{ portfolioAnalysis.message }}</span></div>
          <a-row :gutter="16" class="portfolio-analysis-grid">
            <a-col :xs="24" :lg="12"><a-card size="small" title="决策成功点"><a-list size="small" :data-source="portfolioAnalysis.successPoints || []"><template #renderItem="{ item }"><a-list-item><span class="portfolio-point success">✓</span>{{ item }}</a-list-item></template></a-list><a-empty v-if="!portfolioAnalysis.successPoints?.length" description="暂无明确成功点" /></a-card></a-col>
            <a-col :xs="24" :lg="12"><a-card size="small" title="决策失误点"><a-list size="small" :data-source="portfolioAnalysis.mistakePoints || []"><template #renderItem="{ item }"><a-list-item><span class="portfolio-point mistake">!</span>{{ item }}</a-list-item></template></a-list><a-empty v-if="!portfolioAnalysis.mistakePoints?.length" description="暂无明确失误点" /></a-card></a-col>
            <a-col :xs="24" :lg="12"><a-card size="small" title="原因分析"><a-list size="small" :data-source="portfolioAnalysis.causes || []"><template #renderItem="{ item }"><a-list-item><span class="portfolio-point cause">·</span>{{ item }}</a-list-item></template></a-list><a-empty v-if="!portfolioAnalysis.causes?.length" description="暂无原因分析" /></a-card></a-col>
            <a-col :xs="24" :lg="12"><a-card size="small" title="下一步建议"><a-list size="small" :data-source="portfolioAnalysis.nextSteps || []"><template #renderItem="{ item }"><a-list-item><span class="portfolio-point next">→</span>{{ item }}</a-list-item></template></a-list><a-empty v-if="!portfolioAnalysis.nextSteps?.length" description="暂无下一步建议" /></a-card></a-col>
          </a-row>
          <a-alert v-if="portfolioAnalysis.riskWarnings?.length" type="warning" show-icon message="风险提醒" :description="portfolioAnalysis.riskWarnings.join('；')" />
        </template>
        <a-alert v-else-if="portfolioAnalysis && isPortfolioAnalysisPending(portfolioAnalysis)" type="info" show-icon message="持仓分析正在生成" :description="portfolioAnalysis.message" />
        <a-alert v-else-if="portfolioAnalysis" type="warning" show-icon message="暂时无法生成持仓分析" :description="portfolioAnalysis.message || '请检查持仓数据和AI接入配置'" />
      </a-spin>
      </a-modal>
      <a-modal v-model:open="holdingOverviewVisible" title="持仓总览" :footer="null" width="1120px">
        <a-alert v-if="!holdingOverview.length" type="info" show-icon message="暂无历史持仓" description="请在股票池中点击“维护”，录入已经发生的买入均价和持仓数量。" />
        <template v-else>
          <a-row :gutter="12" class="holding-overview-summary">
            <a-col :xs="12" :sm="5"><a-statistic title="持仓股票" :value="holdingOverview.length" suffix="只" /></a-col>
            <a-col :xs="12" :sm="5"><a-statistic title="持仓数量" :value="holdingOverviewSummary.quantity" suffix="股" /></a-col>
            <a-col :xs="12" :sm="5"><a-statistic title="持仓成本" :value="holdingOverviewSummary.cost" :precision="3" suffix="元" /></a-col>
            <a-col :xs="12" :sm="5"><a-statistic title="持仓市值" :value="holdingOverviewSummary.marketValue" :precision="3" suffix="元" /></a-col>
            <a-col :xs="24" :sm="4"><a-statistic title="合计盈亏" :value="holdingOverviewSummary.pnl" :precision="3" :value-style="{ color: holdingOverviewSummary.pnl >= 0 ? '#cf5151' : '#16803c' }"><template #suffix>元（{{ formatPercentValue(holdingOverviewPnlPercent) }}）</template></a-statistic></a-col>
          </a-row>
          <a-table class="holding-overview-table" :data-source="holdingOverview" :pagination="false" :scroll="{ x: 980 }" row-key="key" size="middle">
            <a-table-column title="股票" key="stock" :width="180"><template #default="{ record }"><div class="stock-name">{{ record.name }} <span>{{ record.code }}</span></div><div class="industry">{{ record.stock?.industry || '—' }}</div></template></a-table-column>
            <a-table-column title="当前价" key="currentPrice" :width="105" align="right"><template #default="{ record }">{{ formatPrice(record.position.currentPrice) }}</template></a-table-column>
            <a-table-column title="成本 / 数量" key="cost" :width="145" align="right"><template #default="{ record }"><div>{{ formatMoney(record.position.buyPrice) }}</div><div class="quote-meta">{{ formatNumber(record.position.quantity) }} 股</div></template></a-table-column>
            <a-table-column title="持仓市值" key="marketValue" :width="125" align="right"><template #default="{ record }">{{ formatMoney(record.position.marketValue) }}</template></a-table-column>
            <a-table-column title="盈亏" key="pnl" :width="140" align="right"><template #default="{ record }"><span :class="record.position.pnlAmount >= 0 ? 'holding-overview-profit' : 'holding-overview-loss'">{{ formatHoldingPnl(record.position.pnlAmount) }}</span><div class="quote-meta" :class="record.position.pnlAmount >= 0 ? 'holding-overview-profit' : 'holding-overview-loss'">{{ formatPercentValue(record.position.pnlPercent) }}</div></template></a-table-column>
            <a-table-column title="仓位" key="positionPercent" :width="125" align="right"><template #default="{ record }"><div>{{ formatPercentValue(record.position.positionPercent) }}</div><div class="quote-meta">上限 {{ formatPercentValue(record.position.maxPositionPercent) }}</div></template></a-table-column>
            <a-table-column title="状态 / 操作" key="status" :width="170"><template #default="{ record }"><a-tag :color="record.position.actionColor || 'default'">{{ record.position.status }}</a-tag><div class="overview-action">{{ record.position.action || '—' }}</div></template></a-table-column>
            <a-table-column title="止损参考" key="stop" :width="120" align="right"><template #default="{ record }">{{ formatNumber(record.tradePlan?.hardStop) }}</template></a-table-column>
          </a-table>
          <div class="footnote">成本按买入均价 × 持仓数量计算；市值、盈亏和仓位使用当前股票池行情与账户资产快照。详细建议请打开对应股票的“维护”。</div>
        </template>
      </a-modal>
  </a-layout>
</template>
