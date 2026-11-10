/** 未登录时的浏览器本地购物车（localStorage） */
const KEY = 'qiqi_local_cart'

const read = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch {
    return []
  }
}

const write = (items) => localStorage.setItem(KEY, JSON.stringify(items))

/** 条目结构：{productSkuId, productId, productName, specText, price, quantity} */
export const listLocalCart = () => read()

export const addLocalCart = (item) => {
  const items = read()
  const exist = items.find((i) => i.productSkuId === item.productSkuId)
  if (exist) {
    exist.quantity += item.quantity || 1
  } else {
    items.push({ ...item, quantity: item.quantity || 1 })
  }
  write(items)
  return items
}

export const updateLocalQuantity = (productSkuId, quantity) => {
  const items = read()
  const item = items.find((i) => i.productSkuId === productSkuId)
  if (item) item.quantity = Math.max(1, quantity)
  write(items)
  return items
}

export const removeLocalCart = (productSkuId) => {
  write(read().filter((i) => i.productSkuId !== productSkuId))
  return read()
}

export const clearLocalCart = () => localStorage.removeItem(KEY)

/** 合并到云端时只需要 skuId + 数量 */
export const toMergePayload = () => read().map((i) => ({ productSkuId: i.productSkuId, quantity: i.quantity }))
