FROM node:24-alpine

WORKDIR /app

# ws e' l'unica dipendenza runtime: Node non ha un server WebSocket nativo.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY src ./src

ENV NODE_ENV=production \
    CRYLOG_PORT=8080 \
    CRYLOG_DATA_DIR=/data

RUN mkdir -p /data && chown -R node:node /data
USER node

EXPOSE 8080

# Il tag della release, passato al build. Sta qui in fondo e non accanto alle
# altre ENV perche' cambia a ogni release: sopra invaliderebbe la cache di
# `npm ci` ogni volta. Senza build-arg resta "dev", che e' la verita' per
# un'immagine costruita a mano.
ARG CRYLOG_VERSION=dev
ENV CRYLOG_VERSION=$CRYLOG_VERSION

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:'+process.env.CRYLOG_PORT+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "src/index.js"]
