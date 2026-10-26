# Moozaq website

Public marketing site for https://moozaq.com. It is a static Astro project in this directory, deployed as the Cloudflare Worker `moozaq` using Workers Static Assets.

## Update the live site

Run these from `website/`, not the repository root. There is no root `package.json`.

```sh
npm install
npm run dev
npm run deploy
```

`npm run dev` previews the site locally. `npm run deploy` builds `dist/` and runs `wrangler deploy`, which publishes a new version of the existing Worker. It does not change DNS. After a deploy, open https://moozaq.com and confirm the change. https://moozaq.coenen-rob.workers.dev serves the same Worker.

Use this project's Wrangler (`npm run deploy`). The account id is in `wrangler.jsonc`. Wrangler on this machine is already logged in as the owner. Do not create an API token, print credentials, or deploy from a different Cloudflare account.

Node.js must satisfy the `engines` field in `package.json`.

## Leave the domain setup alone

These are already done. A content change must not redo them.

- `moozaq.com` is a custom domain on the Worker `moozaq`. DNS is one proxied Worker record.
- There is no `www` record. Do not add one.
- Always Use HTTPS is on. `http://moozaq.com` returns a 301 to `https://moozaq.com/`.
- SSL mode is Full. Do not change nameservers or any other zone.
- Do not use Cloudflare Pages, Netlify, or a Git-connected Cloudflare build. The public source is at https://github.com/rcoenen/Moozaq, but deploy this site from this machine. Mac catalog releases use the separate public `rcoenen/Moozaq-catalog` repository; it is not website content or a Cloudflare build source.
- Do not delete the apex DNS record. The earlier proxied A records pointed at a registrar parking page and were removed on purpose.

## Copy and downloads

The site must say that Moozaq is free, open source, and has no ads, accounts, or subscriptions. That overrides the older “no free forever” line in `docs/website-design-brief.md`. The brief is still the visual reference.

Call each long recording a "vibe", never a "mix": Moozaq plays creators' recordings and does not mix anything. Don't feature particular vibes, counts or durations. The catalog is large and keeps changing, so artwork is used as mood imagery and copy talks in ranges ("an hour or more"). Android and Mac get equal billing. Sonos is shown as coming soon.

The page design is a 1970s hi-fi receiver: harvest-gold paper, a sunset arc and a walnut cabinet holding a brushed-silver mood tuner (`src/components/Receiver.astro`). It follows the OS light or dark setting. The tuner switches between mood panels rendered on the server and never plays audio.

Android and Mac download links are placeholders (`#`) in `src/data/site.ts` until real GitHub release URLs exist. Do not host APKs and do not invent a store listing. The page must not autoplay audio.

`node_modules/`, `dist/`, and `.wrangler/` are gitignored.
