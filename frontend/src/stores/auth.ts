import { create } from 'zustand'

// Access only lives in memory. Refresh is an HttpOnly cookie owned by the server.
interface AuthState {
  accessToken: string | null
  loggingOut: boolean
  setAccessToken: (token: string | null) => void
  setLoggingOut: (loggingOut: boolean) => void
}
export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  loggingOut: false,
  setAccessToken: (accessToken) => set({ accessToken }),
  setLoggingOut: (loggingOut) => set({ loggingOut }),
}))
