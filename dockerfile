# Build stage
FROM node:18-alpine AS build

WORKDIR /app

# Copy custom .npmrc file first
COPY .npmrc ./

# Copy package files
COPY package*.json ./

# Install ALL dependencies
RUN npm ci

# Copy source code
COPY . .

# Build the app
RUN npm run build

# Debug: Show what was built
RUN echo "=== Build completed. Contents of dist ===" && \
    find /app/dist -type f -name "*.html" -o -name "*.js" -o -name "*.css" | head -10

# Production stage
FROM nginx:alpine

# Remove default nginx static content
RUN rm -rf /usr/share/nginx/html/*

# First, copy everything from dist to a temp location
COPY --from=build /app/dist /tmp/angular-dist

# Find the actual build output and copy it to nginx
RUN ANGULAR_BUILD_DIR=$(find /tmp/angular-dist -name "index.html" | head -1 | xargs dirname) && \
    echo "Found Angular build in: $ANGULAR_BUILD_DIR" && \
    cp -r $ANGULAR_BUILD_DIR/* /usr/share/nginx/html/ && \
    ls -la /usr/share/nginx/html/


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

# Create nginx config for port 4200
RUN cat > /etc/nginx/conf.d/default.conf << 'EOF'
server {
    listen 4200;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    # Handle Angular SPA routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache static assets
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
EOF

# Remove the default nginx config that might be interfering
RUN rm -f /etc/nginx/sites-enabled/default

EXPOSE 4200

CMD ["nginx", "-g", "daemon off;"]



# Build the image
# docker build -t my-angular-app .

# # Run the container on port 4200

# docker run -p 4200:4200 my-angular-app







jar 

# Use official OpenJDK runtime as base image
FROM openjdk:17-jre-slim

# Set working directory in container
WORKDIR /app

# Copy the jar file to the container
COPY your-app.jar app.jar

# Expose the port
EXPOSE 9998

# Set default port
ENV PORT=9998

# Run the jar file
CMD ["sh", "-c", "java -jar app.jar --port ${PORT}"]

# Build the image
docker build -t your-app-name .

# Run with port 9998
docker run -p 9998:9998 your-app-name







python 

# Use Python 3.9 slim image
FROM python:3.9-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    g++ \
    && rm -rf /var/lib/apt/lists/*

# Create and activate virtual environment
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy requirements file (create this if you don't have one)
COPY requirements.txt .

# Install Python dependencies in virtual environment
RUN pip install --no-cache-dir -r requirements.txt

# Create the model directory structure
RUN mkdir -p /home/work/python

# Copy your local SentenceTransformer model to the container
COPY ./all-MiniLM-L6-v2 /home/work/python/all-MiniLM-L6-v2

# Copy your Python application
COPY . .

# Expose the port
EXPOSE 8000

# Set environment variables
ENV PYTHONPATH=/app
ENV PATH="/opt/venv/bin:$PATH"

# Run the application
CMD ["python", "main.py"]





