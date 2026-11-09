<template>
  <div class="app">
    <h1>七七商城</h1>
    <p>运行环境: {{ env }}</p>
    <p>后端健康检查: {{ health }}</p>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
const env = import.meta.env.VITE_APP_ENV
const health = ref('检测中...')
onMounted(async () => {
  try {
    const res = await fetch('/api/health')
    health.value = JSON.stringify(await res.json())
  } catch (e) {
    health.value = '后端不可达: ' + e.message
  }
})
</script>
