export function HomePage() {
  return (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-bold">FGC</h1>
      <p className="mt-2 text-gray-600">React 전환 공존기입니다. 기존 화면은 그대로 사용할 수 있습니다.</p>
      <a className="mt-4 inline-block text-blue-700 underline" href="/">
        기존 화면으로 이동
      </a>
    </main>
  )
}
