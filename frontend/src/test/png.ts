/** PNG 시그니처(앞 8바이트) */
export const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

/** 응답 본문을 읽어 PNG 인지와 가로 · 세로(IHDR, 시그니처 8 + 길이 4 + 'IHDR' 4 뒤)를 돌려준다 */
export async function readPng(response: Response): Promise<{
  signature: number[]
  width: number
  height: number
}> {
  const bytes = new Uint8Array(await response.arrayBuffer())
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  return {
    signature: [...bytes.slice(0, 8)],
    width: view.getUint32(16),
    height: view.getUint32(20),
  }
}
