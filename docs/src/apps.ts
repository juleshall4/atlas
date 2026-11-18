// The apps the site has guides for, in the order they show in the header and on the home page.
export interface App {
	/** The first part of the app's guide addresses, such as "atlas" for /atlas/. */
	id: string;
	name: string;
	/** The GitHub description. */
	description: string;
	/** Always three numbers, such as 2.0.0. */
	version: string;
	icon: string;
	github: string;
}

export const apps: App[] = [
	{
		id: 'atlas',
		name: 'Atlas',
		description: 'The all-in-one toolkit to power up your keyboard phone.',
		version: '2.0.0',
		icon: '/images/atlas/icon.png',
		github: 'https://github.com/juleshall4/atlas',
	},
	{
		id: 'maia',
		name: 'Maia',
		description: 'An e-reader made for the Titan 2 Elite.',
		version: '0.1.0',
		icon: '/images/maia/icon.png',
		github: 'https://github.com/juleshall4/maia',
	},
];

/** The newest releases of every app, newest first. The home page shows them under What's new. */
export const releases = [
	{
		date: '16 Nov',
		app: 'Maia',
		version: '0.1.0',
		note: 'First release.',
		href: 'https://github.com/juleshall4/maia',
	},
	{
		date: '14 Nov',
		app: 'Atlas',
		version: '2.0.0',
		note: 'Quick open, text shortcuts, emoji search and better speaker sound.',
		href: '/atlas/whats-new/#version-200',
	},
	{
		date: '9 Nov',
		app: 'Atlas',
		version: '1.3.1',
		note: 'Long swipes work again.',
		href: '/atlas/whats-new/#version-131',
	},
];

export const sponsor = 'https://buymeacoffee.com/juleshall';
export const email = 'juleshall4@icloud.com';

/** The app whose guide a page belongs to, from its address. Undefined on the home page. */
export const appFor = (pathname: string) => apps.find((app) => pathname.startsWith(`/${app.id}/`) || pathname === `/${app.id}`);
