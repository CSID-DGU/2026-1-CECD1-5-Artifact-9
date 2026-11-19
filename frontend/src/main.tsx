import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { installDemoFetch } from './demo/installDemoFetch'

// 로그인 없이 체험하는 데모 — 데모일 때만 /api 요청을 이 탭 안의 가짜 백엔드로 돌린다(그 밖에는 아무것도 안 한다).
installDemoFetch()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
