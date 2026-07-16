/**
 * Builds a `/chat` href that preserves the current `?session=` query parameter (if any).
 *
 * Entering the workspace or returning to chat used to hard-code `/chat`, which dropped the
 * session and let the chat panel fall back to a fresh/another conversation. Routing through
 * this helper keeps the same session end-to-end.
 */
export function chatHrefPreservingSession(): string {
  if (typeof window === 'undefined') return '/chat';
  const params = new URLSearchParams(window.location.search);
  const session = params.get('session');
  return session ? `/chat?session=${encodeURIComponent(session)}` : '/chat';
}
