import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router-dom';

import { chatHrefPreservingSession } from './utils/session';

import LoginPage from './pages/LoginPage';
import AppShell from './components/AppShell';
import AdminAppShell from './components/admin/AdminAppShell';
import EditTierGate from './components/EditTierGate';
import InteractionHost from './components/InteractionHost';

import ChatPage from './pages/ChatPage';
import WorkspacePage from './pages/WorkspacePage';
import SkillsPage from './pages/configure/SkillsPage';
import SubagentsPage from './pages/configure/SubagentsPage';
import ChannelsPage from './pages/configure/ChannelsPage';
import ToolsPage from './pages/configure/ToolsPage';
import SharesPage from './pages/configure/SharesPage';
import SettingsPage from './pages/configure/SettingsPage';

import ProfilePage from './pages/ProfilePage';
import AppearancePage from './pages/AppearancePage';
import ContributionsPage from './pages/ContributionsPage';
import UserBindingsPage from './pages/UserBindingsPage';
import UsagePage from './pages/UsagePage';
import TraceRunsPage from './pages/TraceRunsPage';
import TenantModelsPage from './pages/TenantModelsPage';

import OverviewPage from './pages/admin/OverviewPage';
import ApprovalsPage from './pages/admin/ApprovalsPage';
import InstancesPage from './pages/admin/InstancesPage';
import AdminChannelsPage from './pages/admin/ChannelsPage';
import ConfigPage from './pages/admin/ConfigPage';
import DebugPage from './pages/admin/DebugPage';
import UsersPage from './pages/admin/UsersPage';
import AdminSessionsPage from './pages/admin/SessionsPage';
import AdminAgentsPage from './pages/admin/AgentsPage';
import AdminAgentDetailPage from './pages/admin/AgentDetailPage';
import AdminChannelDetailPage from './pages/admin/ChannelDetailPage';

function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

function decodeJwt(token: string): Record<string, unknown> {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
}

function isAdmin(): boolean {
  const token = getToken();
  if (!token) return false;
  const p = decodeJwt(token);
  const roles = Array.isArray(p.roles) ? (p.roles as string[]) : [];
  return roles.some((r: string) => r.toLowerCase() === 'admin');
}

function PrivateRoute({ children }: { children: React.ReactElement }) {
  return getToken() ? children : <Navigate to="/login" replace />;
}

function AdminRoute({ children }: { children: React.ReactElement }) {
  if (!getToken()) return <Navigate to="/login" replace />;
  if (!isAdmin()) return <Navigate to={chatHrefPreservingSession()} replace />;
  return children;
}

function AdminShellRoute() {
  return (
    <AdminRoute>
      <AdminAppShell><Outlet /></AdminAppShell>
    </AdminRoute>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <InteractionHost />
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<PrivateRoute><AppShell /></PrivateRoute>}>
          <Route index element={<Navigate to={chatHrefPreservingSession()} replace />} />

          {/* 主聊天界面 */}
          <Route path="/chat" element={<ChatPage />} />

          {/* 工作区浏览（RUN 等级只读） */}
          <Route path="/workspace" element={<WorkspacePage />} />

          {/* 配置页面 — 仅 EDIT 等级 */}
          <Route path="/configure/skills"    element={<EditTierGate><SkillsPage /></EditTierGate>} />
          <Route path="/configure/subagents" element={<EditTierGate><SubagentsPage /></EditTierGate>} />
          <Route path="/configure/channels"  element={<EditTierGate><ChannelsPage /></EditTierGate>} />
          <Route path="/configure/tools"     element={<EditTierGate><ToolsPage /></EditTierGate>} />
          <Route path="/configure/shares"    element={<EditTierGate><SharesPage /></EditTierGate>} />
          <Route path="/configure/settings"  element={<EditTierGate><SettingsPage /></EditTierGate>} />

          {/* 用户工具页面 */}
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/appearance" element={<AppearancePage />} />
          <Route path="/contributions" element={<ContributionsPage />} />
          <Route path="/bindings" element={<UserBindingsPage />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/traces" element={<TraceRunsPage />} />
          <Route path="/models" element={<TenantModelsPage />} />

        </Route>

        {/* 管理后台是独立工作台，不能嵌在聊天 AppShell 中。 */}
        <Route element={<AdminShellRoute />}>
          <Route path="/admin/overview"     element={<OverviewPage />} />
          <Route path="/admin/instances"    element={<InstancesPage />} />
          <Route path="/admin/sessions"     element={<AdminSessionsPage />} />
          <Route path="/admin/channels"     element={<AdminChannelsPage />} />
          <Route path="/admin/channels/:id" element={<AdminChannelDetailPage />} />
          <Route path="/admin/agents"       element={<AdminAgentsPage />} />
          <Route path="/admin/agents/:id"   element={<AdminAgentDetailPage />} />
          <Route path="/admin/approvals"    element={<ApprovalsPage />} />
          <Route path="/admin/users"        element={<UsersPage />} />
          <Route path="/admin/usage"        element={<Navigate to="/usage" replace />} />
          <Route path="/admin/config"       element={<ConfigPage />} />
          <Route path="/admin/debug"        element={<DebugPage />} />
        </Route>

        <Route path="*" element={<Navigate to={chatHrefPreservingSession()} replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
