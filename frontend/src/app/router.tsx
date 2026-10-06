import { createBrowserRouter } from 'react-router'
import { routes } from './routes'
export { routes } from './routes'
export const router = createBrowserRouter(routes, { basename: import.meta.env.BASE_URL })
