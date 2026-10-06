import { create } from 'zustand'

// Access only lives in memory. Refresh is an HttpOnly cookie owned by the server.
interface AuthState {
  accessToken: string | null
  setAccessToken: (token: string | null) => void
}
export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  setAccessToken: (accessToken) => set({ accessToken }),
}))
