import { proxyResearchRequest } from "@/lib/server/api-proxy";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return proxyResearchRequest(request, []);
}

export function POST(request: Request) {
  return proxyResearchRequest(request, []);
}
