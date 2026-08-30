# Cline SDK sidecar — Node 22 (SDK requires Node 22+). Runs TypeScript directly via tsx.
FROM node:22-slim

WORKDIR /app

# git is handy for the SDK's future Act-mode work; harmless for v1 Plan-mode Q&A.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY package.json ./
RUN npm install

COPY tsconfig.json ./
COPY src ./src

EXPOSE 8091
CMD ["npm", "start"]
