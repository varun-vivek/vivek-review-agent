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












python 



# Dockerfile
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Create and activate virtual environment
RUN python -m venv /app/venv
ENV PATH="/app/venv/bin:$PATH"

# Ensure we're using the virtual environment
RUN which python && python --version

# Copy requirements file first (for better caching)
COPY requirements.txt .

# Install Python dependencies in virtual environment
RUN pip install --upgrade pip
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Expose port if your Python app serves HTTP
EXPOSE 8000

# Ensure virtual environment is used when running
ENV PATH="/app/venv/bin:$PATH"

# Command to run the application
CMD ["python", "your_main_file.py"]






jar 
# Dockerfile.jar
FROM openjdk:11-jre-slim

# Set working directory
WORKDIR /app

# Copy JAR file and any related files
COPY your-jar-file.jar app.jar
COPY config/ config/
COPY data/ data/
# Add any other directories or files your JAR needs:
# COPY lib/ lib/
# COPY resources/ resources/
# COPY application.properties .

# Expose the port
EXPOSE 9998

# Add health check endpoint test (adjust URL as needed)
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:9998/health || exit 1

# Run the JAR file
CMD ["java", "-jar", "app.jar"]

# Alternative with JVM options:
# CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]

# If your JAR needs specific parameters:
# CMD ["java", "-jar", "app.jar", "--server.port=9998", "--spring.profiles.active=docker"]


