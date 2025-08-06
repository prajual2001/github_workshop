# Riya & Prajwal - Wedding Invitation Website

A beautiful, responsive single-page wedding invitation website built with HTML, CSS, and JavaScript.

## Features ✨

- **Hero Section**: Full-screen background with parallax effects and animated couple names
- **Love Story**: Elegant typography with romantic fonts
- **Invitation Message**: Beautiful gradient background with fade-in animations
- **Event Itinerary**: Interactive carousel showing 3-day wedding schedule
- **Location**: Embedded Google Maps with travel information
- **RSVP Form**: Functional contact form with validation
- **Responsive Design**: Works perfectly on desktop, tablet, and mobile
- **Smooth Animations**: Scroll-triggered animations and transitions
- **Modern UI/UX**: Pastel pink, ivory, and gold color scheme

## File Structure 📁

```
wedding-invitation/
├── index.html          # Main HTML structure
├── styles.css          # All CSS styles and animations
├── script.js           # JavaScript functionality
└── README.md           # This file
```

## Quick Start 🚀

1. Download all files to your computer
2. Open `index.html` in a web browser to preview
3. Customize the content (see customization guide below)
4. Deploy to your preferred hosting service

## Customization Guide 🎨

### 1. Replace Placeholder Images

**Hero Background Image:**
- Replace the gradient in `.background-image` class in `styles.css`
- Add your couple's photo:
```css
.background-image {
    background: url('path/to/your-hero-image.jpg') center/cover;
}
```

**Itinerary Images:**
- Replace gradients in `.day1-image`, `.day2-image`, `.day3-image`
- Add event photos:
```css
.day1-image {
    background: url('path/to/day1-image.jpg') center/cover;
}
```

**Rishikesh Hero Image:**
- Replace gradient in `.itinerary-bg-image`
```css
.itinerary-bg-image {
    background: url('path/to/rishikesh-image.jpg') center/cover;
}
```

### 2. Update Google Maps

Replace the iframe `src` in the Location section with your actual venue coordinates:

```html
<iframe src="https://www.google.com/maps/embed?pb=YOUR_EMBED_CODE_HERE">
```

### 3. Setup RSVP Form

**Option 1: Formspree (Recommended)**
1. Go to [formspree.io](https://formspree.io)
2. Create a free account
3. Get your form endpoint
4. Replace `https://formspree.io/f/your-form-id` in the HTML

**Option 2: Other Form Services**
- Google Forms
- Netlify Forms
- EmailJS

### 4. Color Customization

Edit the CSS variables in `styles.css`:

```css
:root {
    --primary-pink: #f8c8d4;    /* Main pink color */
    --secondary-pink: #f4a4b6;  /* Secondary pink */
    --ivory: #faf7f2;           /* Background ivory */
    --soft-gold: #e8d5b7;       /* Accent gold */
    --dark-text: #4a4a4a;       /* Text color */
}
```

### 5. Font Changes

Current fonts used:
- **Dancing Script**: Decorative script font for couple names
- **Playfair Display**: Elegant serif for headings and quotes
- **Lato**: Clean sans-serif for body text

To change fonts, update the Google Fonts link in HTML and CSS font-family properties.

### 6. Content Updates

**Wedding Date**: Change "08.11.2025" in both HTML and background text
**Names**: Update "Riya & Prajwal" in the hero section
**Love Story**: Edit the love story text in the HTML
**Event Details**: Modify the itinerary cards with your actual schedule
**Location Info**: Update travel instructions for your venue

## Deployment Options 🌐

### 1. GitHub Pages (Free)
1. Create a GitHub repository
2. Upload all files
3. Enable GitHub Pages in repository settings
4. Your site will be live at `https://username.github.io/repository-name`

### 2. Netlify (Free)
1. Drag and drop your folder to [netlify.com](https://netlify.com)
2. Get instant deployment with custom domain options

### 3. Vercel (Free)
1. Upload to [vercel.com](https://vercel.com)
2. Connect with GitHub for automatic deployments

### 4. Traditional Web Hosting
- Upload files to any web hosting service via FTP
- Point your domain to the hosting folder

## Browser Support 🌍

- Chrome (recommended)
- Firefox
- Safari
- Edge
- Mobile browsers (iOS Safari, Android Chrome)

## Performance Tips ⚡

1. **Optimize Images**: Compress images before uploading (use TinyPNG)
2. **Image Formats**: Use WebP format for better compression
3. **Loading**: Consider lazy loading for images below the fold
4. **CDN**: Use a CDN for faster global loading

## Advanced Customizations 🔧

### Add Music/Audio
```javascript
// Add background music (optional)
const audio = new Audio('path/to/wedding-song.mp3');
audio.loop = true;
audio.volume = 0.3;

// Add play/pause button
document.querySelector('.play-music-btn').addEventListener('click', () => {
    audio.paused ? audio.play() : audio.pause();
});
```

### Add Photo Gallery
```html
<!-- Add after itinerary section -->
<section class="gallery-section">
    <h2 class="section-title">Our Journey</h2>
    <div class="photo-grid">
        <!-- Add photo grid here -->
    </div>
</section>
```

### Add Countdown Timer
```javascript
// Add countdown to wedding date
function initCountdown() {
    const weddingDate = new Date('2025-11-08T00:00:00');
    // Countdown logic here
}
```

## Troubleshooting 🔧

**Images not loading**: Check file paths and ensure images are in the correct folder
**Form not working**: Verify form action URL and check spam folder for test submissions
**Animations not smooth**: Ensure JavaScript is enabled and try different browser
**Mobile issues**: Test responsiveness and check viewport meta tag

## Support 💬

For technical issues:
1. Check browser console for errors (F12)
2. Ensure all files are in the same folder
3. Test in different browsers
4. Check that all image paths are correct

## License 📄

This template is free to use for personal wedding websites. Please don't redistribute as a commercial template.

---

**Made with ❤️ for Riya & Prajwal's special day**

*Happy Wedding! 🎉💕*