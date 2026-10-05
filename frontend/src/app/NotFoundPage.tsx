import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-bold">페이지를 찾을 수 없습니다</h1>
      <Link className="mt-4 inline-block text-blue-700 underline" to="/">
        처음으로
      </Link>
    </main>
  )
}
