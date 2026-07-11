import { proxyReadOnlyApiRequest } from "@/lib/server/api-proxy";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return proxyReadOnlyApiRequest(request, "/api/v1/providers/status");
}
