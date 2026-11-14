import starlight from '@astrojs/starlight';
import { defineConfig } from 'astro/config';

export default defineConfig({
	integrations: [
		starlight({
			title: 'Atlas',
			description: 'How to use Atlas on the Unihertz Titan 2 Elite.',
			favicon: '/favicon.svg',
			customCss: ['@fontsource-variable/inter/opsz.css', './src/styles/atlas.css'],
			components: {
				Header: './src/components/Header.astro',
				MobileMenuFooter: './src/components/MobileMenuFooter.astro',
				Sidebar: './src/components/Sidebar.astro',
				PageTitle: './src/components/PageTitle.astro',
				ThemeSelect: './src/components/Empty.astro',
			},
			sidebar: [
				{ label: 'Get started', link: '/' },
				{ label: "What's new", slug: 'whats-new' },
				{
					label: 'Keyboard',
					items: ['keyboard/swiping', 'keyboard/keys', 'keyboard/your-words', 'keyboard/shortcuts'],
				},
				{ label: 'Audio', items: ['audio/speaker', 'audio/calls'] },
				{ label: 'Help', items: ['help/problems', 'help/feedback'] },
			],
		}),
	],
});
