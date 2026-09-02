import { toRaw } from 'vue'

function unwrapReactiveData(value: unknown): unknown {
  const raw = toRaw(value)
  if (Array.isArray(raw)) return (value as unknown[]).map(unwrapReactiveData)
  if (raw && typeof raw === 'object' && (Object.getPrototypeOf(raw) === Object.prototype || Object.getPrototypeOf(raw) === null)) {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, unwrapReactiveData(item)]))
  }
  return raw
}

export function cloneCocModuleData<T>(value: T): T {
  return structuredClone(unwrapReactiveData(value)) as T
}
