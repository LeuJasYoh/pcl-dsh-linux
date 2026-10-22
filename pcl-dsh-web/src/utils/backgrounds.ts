import whale01 from '@/assets/backgrounds/whale-01.png'
import whale02 from '@/assets/backgrounds/whale-02.png'
import whale03 from '@/assets/backgrounds/whale-03.png'
import whale04 from '@/assets/backgrounds/whale-04.png'
import whale05 from '@/assets/backgrounds/whale-05.png'

export interface BuiltinBackground {
  id: string
  value: string
  title: string
  src: string
}

/** Mirrors BackgroundCatalog.java titles and builtin ids. */
export const BACKGROUNDS: BuiltinBackground[] = [
  { id: 'whale-01', value: 'builtin:whale-01', title: '01 晴空招手', src: whale01 },
  { id: 'whale-02', value: 'builtin:whale-02', title: '02 浅海微风', src: whale02 },
  { id: 'whale-03', value: 'builtin:whale-03', title: '03 暖阳小憩', src: whale03 },
  { id: 'whale-04', value: 'builtin:whale-04', title: '04 月色晚安', src: whale04 },
  { id: 'whale-05', value: 'builtin:whale-05', title: '05 初雪围巾', src: whale05 },
]

export const DEFAULT_BACKGROUND = 'builtin:whale-01'

export function findBackground(value: string | null | undefined): BuiltinBackground | null {
  if (!value) return null
  return BACKGROUNDS.find((entry) => entry.value === value) ?? null
}

/**
 * Builtin backgrounds are bundled PNGs (WebP conversion is a follow-up, plan 5.7).
 * Custom local-file paths from the desktop app cannot be served by the browser, so
 * they fall back to the default builtin art instead of showing a broken image.
 */
export function resolveBackground(value: string | null | undefined): string {
  return (findBackground(value) ?? BACKGROUNDS[0]).src
}

export function backgroundTitle(value: string | null | undefined): string {
  const builtin = findBackground(value)
  if (builtin) return builtin.title
  if (value) return '自定义背景（Web 版暂不支持本地文件，显示默认背景）'
  return BACKGROUNDS[0].title
}
