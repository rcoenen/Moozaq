# Website agent notes

Read `README.md` in this directory before editing or deploying. The repository root `Agents.md` still applies, including the rule that nothing may start audio unless the owner asks.

This is a static Astro site. From this directory, `npm run dev` previews it and `npm run deploy` builds and publishes the existing Cloudflare Worker `moozaq`. That command updates https://moozaq.com. The custom domain, DNS, and Always Use HTTPS redirect are already configured. Do not reattach the domain, add `www`, switch to Cloudflare Pages, or connect a GitHub build.

Keep the public claims: free, open source, no ads, no accounts, no subscriptions. Download links stay as placeholders in `src/data/site.ts` until real release URLs exist.
