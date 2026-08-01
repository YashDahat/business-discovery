import axios, { type AxiosInstance } from 'axios'

const apiClient: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  headers: { 'Content-Type': 'application/json' },
})

// Session expiry backstop: if a data call returns 401, send the user to /login.
// Excludes the /api/auth/* probes (they legitimately 401 when signed out — AuthContext
// handles those) and avoids looping when already on the login page.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const url: string = error?.config?.url ?? ''
    const isAuthEndpoint = url.includes('/api/auth/')
    if (status === 401 && !isAuthEndpoint && window.location.pathname !== '/login') {
      window.location.assign('/login')
    }
    return Promise.reject(error)
  },
)

export { apiClient }
