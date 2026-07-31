import React from 'react';
import { isAdmin } from '../api/auth';
import { UsageDashboard } from './admin/UsagePage';

/**
 * Shared usage entry point. Platform-only figures remain behind the admin role,
 * while the regular workspace no longer has a second, reduced usage screen.
 */
export default function UsagePage() {
  const canViewPlatform = isAdmin();
  return (
    <UsageDashboard
      canViewPlatform={canViewPlatform}
      initialScope={canViewPlatform ? 'platform' : 'personal'}
    />
  );
}
