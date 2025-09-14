# Build stage
FROM node:18-alpine AS build

WORKDIR /app

# Copy custom .npmrc file first
COPY .npmrc ./

# Copy package files
COPY package*.json ./

# Install ALL dependencies (including dev dependencies for build)
RUN npm ci

# Copy source code
COPY . .

# Build the app
RUN npm run build

# Production stage
FROM nginx:alpine

# Copy built app to nginx
COPY --from=build /app/dist/frontend /usr/share/nginx/html


COPY nginx.conf /etc/nginx/conf.d/default.conf

# server {
#     listen 4200;
#     server_name localhost;
#     root /usr/share/nginx/html;
#     index index.html;

#     # Handle Angular routing - redirect all routes to index.html
#     location / {
#         try_files $uri $uri/ /index.html;
#     }

#     # Cache static assets
#     location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
#         expires 1y;
#         add_header Cache-Control "public, immutable";
#     }

#     # Security headers
#     add_header X-Frame-Options DENY;
#     add_header X-Content-Type-Options nosniff;
# }

EXPOSE 4200

CMD ["nginx", "-g", "daemon off;"]



# Build the image
# docker build -t my-angular-app .

# # Run the container on port 4200
# docker run -p 4200:4200 my-angular-app